package com.dimadesu.lifestreamer.srtla;

import android.util.Log;

/**
 * JNI wrapper for the native SRTLA library embedded in LifeStreamer.
 * No separate Bond Bunny app is required.
 */
public class NativeSrtlaJni {
    private static final String TAG = "NativeSrtlaJni";

    static {
        try {
            System.loadLibrary("srtla_android");
            Log.i(TAG, "Native SRTLA library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native SRTLA library", e);
        }
    }

    // Core lifecycle
    public static native int     startSrtlaNative(String listenPort, String srtlaHost,
                                                   String srtlaPort,  String ipsFile);
    public static native int     stopSrtlaNative();
    public static native boolean isRunningSrtlaNative();
    public static native boolean isConnected();

    // Network change notification
    public static native void notifyNetworkChange();

    // Virtual IP / socket management
    public static native void setNetworkSocket(String virtualIP, String realIP,
                                               int networkType, int socketFD);

    // UDP socket creation (bound to specific network by SrtlaManager via Java reflection)
    public static native int  createUdpSocketNative();
    public static native void closeSocketNative(int socketFD);
}
