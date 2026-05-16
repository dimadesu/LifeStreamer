/*
 * srtla_jni.cpp - JNI wrapper for embedded SRTLA native library (LifeStreamer)
 *
 * Adapted from bond-bunny's srtla_android_jni.cpp.
 * Java class: com.dimadesu.lifestreamer.srtla.NativeSrtlaJni
 */

#include <jni.h>
#include <pthread.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <errno.h>
#include <atomic>
#include <chrono>
#include <set>
#include <mutex>

// Forward declarations for SRTLA C functions
extern "C" {
    int srtla_start_android(const char* listen_port, const char* srtla_host,
                            const char* srtla_port, const char* ips_file);
    void srtla_stop_android();
    void schedule_update_conns(int signal);

    int srtla_get_connection_count();
    int srtla_get_active_connection_count();
    int srtla_get_connection_details(char* buffer, int buffer_size);
    int srtla_is_reconnecting();

    void srtla_set_network_socket(const char* virtual_ip, const char* real_ip,
                                  int network_type, int socket_fd);
    void srtla_clear_all_sockets();
    void srtla_clear_reconnecting(void);
}

static pthread_t srtla_thread;
static std::atomic<bool> srtla_running(false);
static std::atomic<bool> srtla_should_stop(false);
static std::atomic<int>  srtla_retry_count(0);
static std::atomic<bool> srtla_connected(false);
static std::atomic<bool> srtla_has_ever_connected(false);

static std::set<int> java_owned_fds;
static std::mutex    java_fds_mutex;

struct SrtlaParams {
    char listen_port[16];
    char srtla_host[256];
    char srtla_port[16];
    char ips_file[512];
};

static void* srtla_thread_func(void* args) {
    SrtlaParams* params = (SrtlaParams*)args;
    const int RETRY_DELAY_MS = 3000;
    const int INITIAL_CONNECTION_TIMEOUT_MS = 5000;

    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI",
                        "Starting SRTLA thread: host=%s port=%s",
                        params->srtla_host, params->srtla_port);

    srtla_retry_count.store(0);
    srtla_connected.store(false);
    srtla_has_ever_connected.store(false);

    auto thread_start_time = std::chrono::steady_clock::now();

    while (!srtla_should_stop.load()) {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Calling srtla_start_android()...");
        int result = srtla_start_android(params->listen_port, params->srtla_host,
                                         params->srtla_port, params->ips_file);
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI",
                            "srtla_start_android() returned: %d", result);

        if (srtla_should_stop.load()) break;

        srtla_connected.store(false);

        auto now = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                           now - thread_start_time).count();

        if (srtla_has_ever_connected.load() || elapsed > INITIAL_CONNECTION_TIMEOUT_MS) {
            srtla_retry_count.fetch_add(1);
        }

        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI",
                            "Retrying in %dms (attempt %d)",
                            RETRY_DELAY_MS, srtla_retry_count.load());

        for (int i = 0; i < RETRY_DELAY_MS / 100 && !srtla_should_stop.load(); i++) {
            usleep(100 * 1000);
        }
    }

    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA thread exiting");
    delete params;
    srtla_running.store(false);
    srtla_retry_count.store(0);
    srtla_connected.store(false);
    srtla_has_ever_connected.store(false);
    return nullptr;
}

// Called by native SRTLA when connection to receiver is established
extern "C" void srtla_on_connection_established() {
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Connection established callback");
    bool wasConnected = srtla_connected.load();
    srtla_connected.store(true);
    srtla_has_ever_connected.store(true);
    srtla_clear_reconnecting();
    if (!wasConnected) {
        srtla_retry_count.store(0);
    }
}

// Called by native SRTLA to check if a file descriptor is Java-owned
extern "C" int srtla_is_java_owned_fd(int fd) {
    std::lock_guard<std::mutex> lock(java_fds_mutex);
    return java_owned_fds.count(fd) ? 1 : 0;
}

// ---------------------------------------------------------------------------
// JNI methods for com.dimadesu.lifestreamer.srtla.NativeSrtlaJni
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL
Java_com_dimadesu_lifestreamer_srtla_NativeSrtlaJni_startSrtlaNative(
        JNIEnv *env, jclass, jstring listen_port, jstring srtla_host,
        jstring srtla_port, jstring ips_file) {

    if (srtla_running.load()) {
        __android_log_print(ANDROID_LOG_WARN, "SRTLA-JNI", "Already running, ignoring start");
        return -1;
    }

    const char* c_listen_port = env->GetStringUTFChars(listen_port, nullptr);
    const char* c_srtla_host  = env->GetStringUTFChars(srtla_host,  nullptr);
    const char* c_srtla_port  = env->GetStringUTFChars(srtla_port,  nullptr);
    const char* c_ips_file    = env->GetStringUTFChars(ips_file,    nullptr);

    SrtlaParams* params = new SrtlaParams();
    strncpy(params->listen_port, c_listen_port, sizeof(params->listen_port) - 1);
    strncpy(params->srtla_host,  c_srtla_host,  sizeof(params->srtla_host)  - 1);
    strncpy(params->srtla_port,  c_srtla_port,  sizeof(params->srtla_port)  - 1);
    strncpy(params->ips_file,    c_ips_file,    sizeof(params->ips_file)    - 1);

    env->ReleaseStringUTFChars(listen_port, c_listen_port);
    env->ReleaseStringUTFChars(srtla_host,  c_srtla_host);
    env->ReleaseStringUTFChars(srtla_port,  c_srtla_port);
    env->ReleaseStringUTFChars(ips_file,    c_ips_file);

    srtla_should_stop.store(false);
    srtla_retry_count.store(0);
    srtla_connected.store(false);
    srtla_has_ever_connected.store(false);
    srtla_running.store(true);

    if (pthread_create(&srtla_thread, nullptr, srtla_thread_func, params) != 0) {
        srtla_running.store(false);
        delete params;
        __android_log_print(ANDROID_LOG_ERROR, "SRTLA-JNI", "Failed to create SRTLA thread");
        return -1;
    }

    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA thread started");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dimadesu_lifestreamer_srtla_NativeSrtlaJni_stopSrtlaNative(JNIEnv*, jclass) {
    if (!srtla_running.load()) {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Not running, nothing to stop");
        return 0;
    }

    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Stopping SRTLA...");
    srtla_should_stop.store(true);
    srtla_stop_android();
    srtla_clear_all_sockets();

    int wait_count = 0;
    while (srtla_running.load() && wait_count < 50) {
        usleep(100000);
        wait_count++;
    }

    if (wait_count >= 50) {
        __android_log_print(ANDROID_LOG_WARN, "SRTLA-JNI", "Thread did not exit in time, detaching");
        pthread_detach(srtla_thread);
    } else {
        void* thread_result;
        pthread_join(srtla_thread, &thread_result);
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI",
                            "Thread joined after %d ms", wait_count * 100);
    }

    srtla_running.store(false);
    srtla_should_stop.store(false);
    srtla_retry_count.store(0);
    srtla_connected.store(false);
    srtla_has_ever_connected.store(false);
    {
        std::lock_guard<std::mutex> lock(java_fds_mutex);
        java_owned_fds.clear();
    }

    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA fully stopped");
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dimadesu_lifestreamer_srtla_NativeSrtlaJni_isRunningSrtlaNative(JNIEnv*, jclass) {
    return srtla_running.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dimadesu_lifestreamer_srtla_NativeSrtlaJni_isConnected(JNIEnv*, jclass) {
    return srtla_connected.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dimadesu_lifestreamer_srtla_NativeSrtlaJni_notifyNetworkChange(JNIEnv*, jclass) {
    if (srtla_running.load()) {
        schedule_update_conns(0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dimadesu_lifestreamer_srtla_NativeSrtlaJni_setNetworkSocket(
        JNIEnv *env, jclass, jstring virtual_ip, jstring real_ip,
        jint network_type, jint socket_fd) {

    const char* vip = env->GetStringUTFChars(virtual_ip, nullptr);
    const char* rip = env->GetStringUTFChars(real_ip,    nullptr);

    {
        std::lock_guard<std::mutex> lock(java_fds_mutex);
        java_owned_fds.insert(socket_fd);
    }
    srtla_set_network_socket(vip, rip, network_type, socket_fd);

    env->ReleaseStringUTFChars(virtual_ip, vip);
    env->ReleaseStringUTFChars(real_ip,    rip);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dimadesu_lifestreamer_srtla_NativeSrtlaJni_createUdpSocketNative(JNIEnv*, jclass) {
    int sockfd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK, 0);
    if (sockfd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "SRTLA-JNI",
                            "Failed to create UDP socket: %s", strerror(errno));
        return -1;
    }
    int bufsize = 212992;
    setsockopt(sockfd, SOL_SOCKET, SO_SNDBUF, &bufsize, sizeof(bufsize));
    setsockopt(sockfd, SOL_SOCKET, SO_RCVBUF, &bufsize, sizeof(bufsize));
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Created UDP socket FD: %d", sockfd);
    return sockfd;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dimadesu_lifestreamer_srtla_NativeSrtlaJni_closeSocketNative(
        JNIEnv*, jclass, jint sockfd) {
    if (sockfd >= 0) {
        close(sockfd);
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Closed socket FD: %d", sockfd);
    }
}
