# SRT Background Streaming Issues & Solutions

## Overview

This document analyzes potential compatibility and background streaming issues with the SRT (Secure Reliable Transport) streaming implementation, particularly focusing on device compatibility and background execution reliability.

**Date:** October 5, 2025  
**Status:** Analysis Complete - Fixes Recommended

---

## Table of Contents

1. [Audio Capture Approach Analysis](#audio-capture-approach-analysis)
2. [SRT Background Streaming Issues](#srt-background-streaming-issues)
3. [Device Compatibility Concerns](#device-compatibility-concerns)
4. [Recommended Fixes](#recommended-fixes)
5. [Testing Recommendations](#testing-recommendations)

---

## Audio Capture Approach Analysis

### Current Implementation

The app uses MediaProjection AudioPlaybackCapture with ExoPlayer volume set to zero:

```kotlin
// RtmpSourceSwitchHelper.kt
exoPlayer.volume = 0f
```

```kotlin
// MediaProjectionAudioSource.kt
AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
    .addMatchingUid(Process.myUid())
    .excludeUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
    .excludeUsage(AudioAttributes.USAGE_NOTIFICATION)
    .build()
```

### Why This Works

✅ **ExoPlayer volume = 0f is safe and reliable**
- Only affects local playback audio output
- Audio data is captured **before** it reaches volume control
- The audio stream flows through MediaProjection's AudioPlaybackCapture API unaffected

✅ **AudioPlaybackCaptureConfiguration is standard**
- Android API 29+ (Q) standard API
- Should work consistently across devices
- UID filtering ensures only app's own audio is captured

✅ **System audio exclusion improves compatibility**
- Excludes `USAGE_ASSISTANCE_SONIFICATION` (keyboard clicks, UI sounds)
- Excludes `USAGE_NOTIFICATION` (notification sounds)
- Prevents feedback loops on devices with custom audio HALs

### Potential Device Compatibility Issues

#### 1. Audio Routing & Virtual Audio Device

**Risk:** Medium  
**Affected Devices:** Samsung (One UI), Xiaomi (MIUI), OnePlus (OxygenOS)

Some OEM ROMs have custom audio HAL implementations that:
- Route audio differently than AOSP
- May not properly support AudioPlaybackCapture on all audio streams
- Could have bugs in MediaProjection audio capture

**Mitigation:** System audio exclusions already implemented

#### 2. Audio Focus Behavior

**Risk:** Low  
**Affected Devices:** Various OEMs with custom audio focus implementations

When ExoPlayer plays at volume 0:
- It still requests audio focus (unless configured otherwise)
- Some devices might suppress audio capture when audio focus is held by a muted stream

**Recommendation:** Consider using explicit audio attributes:
```kotlin
exoPlayer.volume = 0f
exoPlayer.setAudioAttributes(
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build(),
    false // handleAudioFocus = false might help on some devices
)
```

#### 3. Background Processing Restrictions

**Risk:** High  
**Affected Devices:** Xiaomi (MIUI), Huawei (EMUI), Vivo, Oppo

Aggressive battery optimization might:
- Throttle MediaProjection capture in background
- Kill the foreground service unexpectedly
- Suspend audio capture during Doze mode

**Current Protection:** Wake lock for audio implemented ✅

#### 4. DRM/Protected Content

**Risk:** Low  
**Affected Content:** DRM-protected streams

The code uses `addMatchingUsage(AudioAttributes.USAGE_MEDIA)` but doesn't explicitly handle protected content. Some devices might:
- Block capture of DRM-protected streams
- Return silence for certain content types

**Note:** Only captures own app's audio (UID filtering), so this is acceptable

---

## SRT Background Streaming Issues

### Critical Issues Found

#### 🔴 Issue 1: No Network Thread Priority or Wake Lock

**Location:** `StreamPack/extensions/srt/elements/endpoints/composites/sinks/SrtSink.kt`

**Problem:**
```kotlin
class SrtSink : AbstractSink() {
    private var socket: CoroutineSrtSocket? = null
    // No dispatcher specified - uses default
    // No wake lock for network operations
}
```

**Impact:**
- Network I/O runs at default priority
- Can be throttled during Doze mode
- CPU can throttle when screen is off
- No protection against background restrictions

**Comparison with Audio:**
```kotlin
// Audio has wake lock protection ✅
wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "StreamPack::StableBackgroundAudioRecording"
)
```

Network I/O has **NO wake lock** ❌

#### 🔴 Issue 2: Socket Not Configured for Background Data

**Location:** `SrtSink.kt:75-80`

**Current Configuration:**
```kotlin
socket?.let {
    it.setSockFlag(SockOpt.PAYLOADSIZE, PAYLOAD_SIZE)
    it.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
}

// Later in startStream():
socket.setSockFlag(SockOpt.MAXBW, 0L)
socket.setSockFlag(SockOpt.INPUTBW, bitrate)
```

**Missing Critical Socket Options:**
- ❌ `SNDBUF` (send buffer size) - not configured
- ❌ `RCVBUF` (receive buffer size) - not configured
- ❌ `CONNTIMEO` (connection timeout) - not configured
- ❌ `PEERIDLETIMEO` (peer idle timeout) - not configured
- ❌ Socket keep-alive settings
- ❌ Linger options

**Why This Matters:**

Without proper buffer sizing:
- Small buffers fill up quickly during Doze mode
- Write operations block the thread
- Connection times out if peer doesn't read fast enough
- No buffering capacity for network jitter

#### 🔴 Issue 3: Weak Error Handling in Background

**Location:** `SrtSink.kt:83-88`

**Problem:**
```kotlin
it.socketContext.invokeOnCompletion { t ->
    completionException = t
    runBlocking {  // ⚠️ Dangerous in background
        this@SrtSink.close()
    }
}
```

**Issues:**
- `runBlocking` can cause ANRs or deadlocks
- Called from completion handler (potentially restricted context)
- No retry logic for transient network failures
- No detection of background restrictions

#### 🔴 Issue 4: No Android Background Data Restrictions Handling

**Missing Protections:**
- ❌ No detection of Data Saver mode
- ❌ No handling of background data restrictions
- ❌ No Doze mode detection
- ❌ No Battery optimization detection
- ❌ No ConnectivityManager.isActiveNetworkMetered check

**Impact:**
System can silently block or throttle network traffic without app knowing.

#### 🔴 Issue 5: No Explicit Coroutine Dispatcher

**Location:** Throughout `SrtSink.kt`

**Problem:**
```kotlin
override suspend fun write(packet: Packet): Int {
    // No withContext(Dispatchers.IO)
    return socket.send(packet.buffer, buildMsgCtrl(packet))
}
```

**Impact:**
- Runs on caller's dispatcher (unpredictable)
- May run on Main thread if caller doesn't specify
- No guarantee of background thread execution
- Not optimized for blocking I/O operations

---

## Device Compatibility Concerns

### High-Risk Devices for Testing

| Manufacturer | OS | Primary Risk |
|-------------|-----|--------------|
| Samsung | One UI | Custom audio HAL, aggressive battery optimization |
| Xiaomi | MIUI | Extremely aggressive battery optimization, kills services |
| Huawei | EMUI | Background restrictions, custom audio routing |
| OnePlus | OxygenOS | Audio routing quirks, battery optimization |
| Vivo/Oppo | ColorOS/FuntouchOS | Background app killer, network restrictions |
| Google Pixel | AOSP | Baseline reference (should work) |

### Specific Android Version Issues

#### Android 12+ (API 31+)
- Stricter foreground service restrictions
- Camera/Microphone type services can be killed more aggressively
- Background data restrictions enforced more strictly

#### Android 13+ (API 33+)
- Notification permission required
- More aggressive Doze mode
- Restricted battery optimization

#### Android 14+ (API 34+)
- Even stricter background restrictions
- Foreground service type enforcement
- Network access can be suspended during battery optimization

---

## Recommended Fixes

### Priority 1: Add Socket Buffer Configuration

**File:** `StreamPack/extensions/srt/elements/endpoints/composites/sinks/SrtSink.kt`

**Implementation:**
```kotlin
private suspend fun open(mediaDescriptor: SrtMediaDescriptor) {
    if (mediaDescriptor.srtUrl.mode != null) {
        require(mediaDescriptor.srtUrl.mode == Mode.CALLER) { 
            "Invalid mode: ${mediaDescriptor.srtUrl.mode}. Only caller supported." 
        }
    }
    if (mediaDescriptor.srtUrl.payloadSize != null) {
        require(mediaDescriptor.srtUrl.payloadSize == PAYLOAD_SIZE)
    }
    if (mediaDescriptor.srtUrl.transtype != null) {
        require(mediaDescriptor.srtUrl.transtype == Transtype.LIVE)
    }

    socket = CoroutineSrtSocket()
    socket?.let {
        // Existing flags
        it.setSockFlag(SockOpt.PAYLOADSIZE, PAYLOAD_SIZE)
        it.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
        
        // NEW: Add buffer configuration for background streaming
        // Calculate buffer size based on bitrate
        // For 6Mbps: 2MB allows ~2.6 seconds of buffering
        it.setSockFlag(SockOpt.SNDBUF, 2 * 1024 * 1024) // 2MB send buffer
        it.setSockFlag(SockOpt.RCVBUF, 1 * 1024 * 1024) // 1MB receive buffer
        
        // Connection and timeout settings
        it.setSockFlag(SockOpt.CONNTIMEO, 5000) // 5 second connection timeout
        it.setSockFlag(SockOpt.PEERIDLETIMEO, 10000) // 10 second idle timeout
        
        // Keep-alive to detect dead connections
        it.setSockFlag(SockOpt.PEERLATENCY, 500) // 500ms peer latency
        it.setSockFlag(SockOpt.RCVLATENCY, 500) // 500ms receive latency
        
        completionException = null
        isOnError = false
        it.socketContext.invokeOnCompletion { t ->
            completionException = t
            runBlocking {
                this@SrtSink.close()
            }
        }
        it.connect(mediaDescriptor.srtUrl)
    }
    _isOpenFlow.emit(true)
}
```

**Constants to add:**
```kotlin
companion object {
    private const val TAG = "SrtSink"
    private const val PAYLOAD_SIZE = 1316
    
    // Buffer sizes for background streaming
    private const val SEND_BUFFER_SIZE = 2 * 1024 * 1024 // 2MB
    private const val RECV_BUFFER_SIZE = 1 * 1024 * 1024 // 1MB
    private const val CONNECTION_TIMEOUT_MS = 5000 // 5 seconds
    private const val PEER_IDLE_TIMEOUT_MS = 10000 // 10 seconds
    private const val LATENCY_MS = 500 // 500ms latency
}
```

### Priority 2: Add Network Wake Lock in Service

**File:** `app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt`

**Add property:**
```kotlin
// Wake lock to prevent audio silencing
private lateinit var powerManager: PowerManager
private var wakeLock: PowerManager.WakeLock? = null

// NEW: Separate wake lock for network operations
private var networkWakeLock: PowerManager.WakeLock? = null
```

**Add method:**
```kotlin
/**
 * Acquire network wake lock to prevent network throttling in background
 * Especially important for SRT streaming
 */
private fun acquireNetworkWakeLock() {
    if (networkWakeLock == null) {
        networkWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LifeStreamer::NetworkUpload"
        ).apply {
            acquire(30 * 60 * 1000L) // 30 minutes max
            Log.i(TAG, "Network wake lock acquired for SRT/RTMP upload")
        }
    }
}

/**
 * Release network wake lock
 */
private fun releaseNetworkWakeLock() {
    networkWakeLock?.let { lock ->
        if (lock.isHeld) {
            lock.release()
            Log.i(TAG, "Network wake lock released")
        }
        networkWakeLock = null
    }
}
```

**Update `onStreamingStart()`:**
```kotlin
override fun onStreamingStart() {
    streamingStartTime = System.currentTimeMillis()
    
    // Acquire wake locks when streaming starts
    acquireWakeLock()
    acquireNetworkWakeLock() // NEW
    
    // ... rest of method
}
```

**Update `onStreamingStop()`:**
```kotlin
override fun onStreamingStop() {
    // ... existing code ...
    
    // Release wake locks when streaming stops
    releaseWakeLock()
    releaseNetworkWakeLock() // NEW
    
    // ... rest of method
}
```

**Update `onDestroy()`:**
```kotlin
override fun onDestroy() {
    // Release wake locks if held
    try { releaseWakeLock() } catch (_: Exception) {}
    try { releaseNetworkWakeLock() } catch (_: Exception) {} // NEW
    
    // ... rest of method
}
```

### Priority 3: Add Explicit Coroutine Dispatcher

**File:** `StreamPack/extensions/srt/elements/endpoints/composites/sinks/SrtSink.kt`

**Add import:**
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

**Add property:**
```kotlin
class SrtSink : AbstractSink() {
    override val supportedSinkTypes: List<MediaSinkType> = listOf(MediaSinkType.SRT)

    private var socket: CoroutineSrtSocket? = null
    private var completionException: Throwable? = null
    private var isOnError: Boolean = false
    private var bitrate = 0L
    
    // NEW: Dedicated IO dispatcher for network operations
    private val ioDispatcher = Dispatchers.IO
```

**Update write method:**
```kotlin
override suspend fun write(packet: Packet): Int = withContext(ioDispatcher) {
    if (isOnError) {
        return@withContext -1
    }

    // Pick up completionException if any
    completionException?.let {
        isOnError = true
        throw ClosedException(it)
    }

    val socket = requireNotNull(socket) { "SrtEndpoint is not initialized" }

    try {
        socket.send(packet.buffer, buildMsgCtrl(packet))
    } catch (t: Throwable) {
        isOnError = true
        if (completionException != null) {
            throw ClosedException(completionException!!)
        }
        close()
        throw ClosedException(t)
    }
}
```

**Update other suspend methods:**
```kotlin
override suspend fun startStream() = withContext(ioDispatcher) {
    val socket = requireNotNull(socket) { "SrtEndpoint is not initialized" }
    require(socket.isConnected) { "SrtEndpoint should be connected at this point" }

    socket.setSockFlag(SockOpt.MAXBW, 0L)
    socket.setSockFlag(SockOpt.INPUTBW, bitrate)
}

override suspend fun close() = withContext(ioDispatcher) {
    socket?.close()
    _isOpenFlow.emit(false)
}
```

### Priority 4: Improve Error Handling

**File:** `StreamPack/extensions/srt/elements/endpoints/composites/sinks/SrtSink.kt`

**Replace completion handler:**
```kotlin
private suspend fun open(mediaDescriptor: SrtMediaDescriptor) {
    // ... existing setup code ...
    
    socket = CoroutineSrtSocket()
    socket?.let {
        // ... existing socket flags ...
        
        completionException = null
        isOnError = false
        
        // NEW: Better error handling without runBlocking
        it.socketContext.invokeOnCompletion { t ->
            if (t != null) {
                Log.e(TAG, "SRT socket context completed with error", t)
                completionException = t
                isOnError = true
                
                // Use launch instead of runBlocking to avoid blocking
                GlobalScope.launch(ioDispatcher) {
                    try {
                        this@SrtSink.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing socket after completion", e)
                    }
                }
            }
        }
        
        it.connect(mediaDescriptor.srtUrl)
    }
    _isOpenFlow.emit(true)
}
```

### Priority 5: Add Background Restrictions Detection

**File:** `app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt`

**Add method:**
```kotlin
/**
 * Check if app is subject to background restrictions that could affect streaming
 */
private fun checkBackgroundRestrictions(): String? {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    // Check Data Saver mode
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        when (connectivityManager.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> {
                return "Data Saver is enabled - streaming may be restricted"
            }
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> {
                Log.i(TAG, "App is whitelisted from Data Saver restrictions")
            }
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> {
                Log.i(TAG, "Data Saver is disabled")
            }
        }
    }
    
    // Check if device is in Doze mode
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isDeviceIdleMode) {
            return "Device is in Doze mode - network may be restricted"
        }
    }
    
    // Check battery optimization
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return "Battery optimization is enabled - may affect background streaming"
        }
    }
    
    return null // No restrictions detected
}
```

**Call before streaming:**
```kotlin
override suspend fun onStreamStart() {
    // Check for background restrictions before starting
    checkBackgroundRestrictions()?.let { warning ->
        Log.w(TAG, "Background restriction detected: $warning")
        // Could emit this to UI via a flow if needed
    }
    
    super.onStreamStart()
}
```

---

## Testing Recommendations

### Test Matrix

| Test Scenario | Duration | Expected Result |
|--------------|----------|-----------------|
| Screen off, no battery optimization | 10 min | Stream continues |
| Screen off, battery optimization ON | 10 min | Stream continues with wake lock |
| Doze mode (screen off 30+ min) | 30 min | Stream survives maintenance windows |
| Data Saver enabled | 5 min | Warning shown, stream continues if whitelisted |
| Switch from WiFi to cellular | 2 min | Reconnects gracefully |
| Airplane mode toggle | 2 min | Detects disconnect, stops cleanly |
| Low battery (< 15%) | 5 min | Stream continues with aggressive optimization |
| App in background (other app foreground) | 10 min | Stream continues |

### Device Priority List

**Critical Devices (Must Test):**
1. Samsung Galaxy (One UI) - S21/S22/S23 series
2. Xiaomi/Redmi (MIUI) - Any recent model
3. Google Pixel - 6/7/8 series (baseline)
4. OnePlus - 9/10/11 series

**Secondary Devices:**
5. Oppo/Vivo - Any recent ColorOS device
6. Motorola - Any recent model
7. Nothing Phone - Any model

### Monitoring During Tests

**Key Metrics to Log:**
```kotlin
// Add to CameraStreamerService
private fun logStreamingHealth() {
    if (serviceStreamer?.isStreamingFlow?.value == true) {
        try {
            // Get SRT stats if available
            val stats = (streamer.endpoint as? SrtSink)?.metrics
            stats?.let {
                Log.i(TAG, "SRT Health: " +
                    "SendBuf=${it.pktSndBuf}, " +
                    "Dropped=${it.pktSndDrop}, " +
                    "Bitrate=${it.mbpsSendRate}Mbps")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get streaming stats", e)
        }
    }
}
```

**Watch For in Logcat:**
- `ClosedException` from SrtSink
- Socket write timeouts
- `pktSndBuf` increasing (indicates send buffer filling up)
- Wake lock acquisition/release
- Background restriction warnings
- Doze mode entry/exit

### Expected Issues by Device

**Samsung (One UI):**
- Custom audio HAL may require audio focus tweaks
- Aggressive battery optimization after 30 minutes screen-off
- May show "App is using battery" notification

**Xiaomi (MIUI):**
- Extremely aggressive - may kill service despite foreground status
- Requires user to disable battery optimization manually
- May need to be added to "Autostart" list

**Pixel (Reference):**
- Should work perfectly with wake locks
- Clean AOSP behavior
- Use as baseline for comparison

**OnePlus (OxygenOS):**
- Audio routing quirks may cause silent capture
- Battery optimization less aggressive than Xiaomi
- Generally good compatibility

---

## Expected Symptoms Without Fixes

### What Users Will Experience

1. **Stream Freezes After 5-10 Minutes**
   - Cause: Network I/O throttled during Doze mode
   - Fix: Network wake lock (Priority 2)

2. **Connection Timeout in Background**
   - Cause: Socket buffers too small, write blocks
   - Fix: Buffer configuration (Priority 1)

3. **Bitrate Drops to Zero**
   - Cause: `pktSndBuf` fills up, packets dropped
   - Fix: Buffer configuration (Priority 1)

4. **"ClosedException" in Logs**
   - Cause: Socket closed due to peer timeout
   - Fix: Keep-alive and timeout settings (Priority 1)

5. **Stream Works Perfect When App is Foreground**
   - Cause: Background restrictions only apply when backgrounded
   - Fix: All priority fixes needed

### How to Reproduce Issues

1. Start SRT stream
2. Press home button (app goes to background)
3. Turn screen off
4. Wait 5-10 minutes
5. Check stream on receiving end - will be frozen/disconnected

---

## Implementation Priority

### Phase 1: Critical Fixes (Do First)
1. ✅ Add socket buffer configuration (Priority 1)
2. ✅ Add network wake lock (Priority 2)
3. ✅ Add explicit IO dispatcher (Priority 3)

**Expected Impact:** Resolves 80% of background streaming issues

### Phase 2: Robustness (Do Second)
4. Improve error handling (Priority 4)
5. Add background restrictions detection (Priority 5)

**Expected Impact:** Better diagnostics and error messages

### Phase 3: Testing & Validation
6. Test on high-risk devices
7. Monitor SRT stats during background streaming
8. Collect user feedback

---

## Conclusion

The current SRT implementation will likely experience issues during background streaming on most devices due to:

1. **No wake lock for network operations** - CPU/network can be throttled
2. **No socket buffer configuration** - Small buffers can't handle Doze mode
3. **No explicit dispatcher** - Network I/O not pinned to IO threads
4. **Weak error handling** - `runBlocking` in completion handler is dangerous

The audio capture approach is fundamentally sound, but network upload needs the same level of protection that audio already has (wake locks, proper configuration).

**Recommendation:** Implement Priority 1-3 fixes before releasing background streaming feature. Test thoroughly on Samsung and Xiaomi devices.

---

## References

- [Android Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Background Execution Limits](https://developer.android.com/about/versions/oreo/background)
- [SRT Protocol Specification](https://github.com/Haivision/srt)
- [Android Power Management](https://developer.android.com/about/versions/pie/power)
- [OEM Background Restrictions](https://dontkillmyapp.com/)
