# Orientation Handling Analysis

## Overview

This document analyzes how orientation changes are currently handled in LifeStreamer vs the original StreamPack demo, and explores solutions for the confusing mismatch between UI orientation and stream orientation.

**Date:** October 6, 2025  
**Issue:** UI can be in portrait while stream is in landscape, causing confusion

---

## Current Implementation

### Your App (LifeStreamer)

**Orientation Locking During Streaming:**

```kotlin
// PreviewFragment.kt:265
private fun lockOrientation() {
    /**
     * Lock orientation while stream is running to avoid stream interruption if
     * user turns the device.
     * For landscape only mode, set [requireActivity().requestedOrientation] to
     * [ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE].
     */
    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
}

private fun unlockOrientation() {
    requireActivity().requestedOrientation = ApplicationConstants.supportedOrientation
}
```

**Problem:** `SCREEN_ORIENTATION_LOCKED` locks to the **current** orientation at the time streaming starts. This means:

- If you start streaming in portrait, it locks to portrait
- But the **stream itself** can be in landscape (sensor rotation != UI orientation)
- This creates a mismatch where UI shows portrait but stream is landscape

### Original StreamPack Demo

**Same approach:**

```kotlin
// StreamPack/demos/camera/.../PreviewFragment.kt:123
private fun lockOrientation() {
    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
}

private fun unlockOrientation() {
    requireActivity().requestedOrientation = ApplicationConstants.supportedOrientation
}
```

**Key Difference:** The original demo probably didn't have custom rotation handling that allows decoupling UI from stream orientation.

---

## How Rotation Works in Your App

### 1. UI Orientation vs Stream Orientation

**UI Orientation:**

- Controlled by `Activity.requestedOrientation`
- Can be portrait, landscape, locked, or sensor-based
- Determined by `ApplicationConstants.supportedOrientation`

**Stream Orientation:**

- Controlled by `streamer.setTargetRotation(rotation)`
- Driven by `SensorRotationProvider` in `CameraStreamerService`
- Updates based on **device sensor orientation**, not UI orientation

### 2. Rotation Providers

**ViewModel uses `RotationRepository`:**

```kotlin
// PreviewViewModel.kt:94
private val rotationRepository = RotationRepository.getInstance(application)

// PreviewViewModel.kt:560-572
viewModelScope.launch {
    rotationRepository.rotationFlow.collect { rotation ->
        if (isStreamingFlow.value == true) {
            Log.i(TAG, "Rotation change to $rotation queued until stream stops")
            pendingTargetRotation = rotation
        } else {
            try {
                current?.setTargetRotation(rotation)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to set target rotation: $t")
            }
        }
    }
}
```

**Service uses `SensorRotationProvider`:**

```kotlin
// CameraStreamerService.kt:212-226
serviceScope.launch(Dispatchers.Default) {
    try {
        val rotationProvider = SensorRotationProvider(this@CameraStreamerService)
        val listener = object : IRotationProvider.Listener {
            override fun onOrientationChanged(rotation: Int) {
                try {
                    serviceScope.launch {
                        try {
                            (streamer as? IWithVideoRotation)?.setTargetRotation(rotation)
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
        }
        rotationProvider.addListener(listener)
        localRotationProvider = rotationProvider
```

**Key Insight:**

- `SensorRotationProvider` follows the **device physical orientation** (accelerometer)
- `DisplayRotationProvider` follows the **screen/UI orientation** (what the display shows)
- Your app uses `SensorRotationProvider`, so stream rotation is based on physical device angle
- But UI is locked to current orientation when streaming starts

### 3. The Mismatch Scenario

**Example:**

1. User holds phone in portrait mode
2. User starts streaming → UI locks to portrait
3. User rotates device to landscape physically
4. `SensorRotationProvider` detects landscape → stream rotates to landscape
5. But UI is still locked to portrait (because of `SCREEN_ORIENTATION_LOCKED`)
6. **Result:** Portrait UI showing landscape stream content

---

## Root Cause Analysis

### Why This Happens

1. **UI locks to current orientation** when streaming starts (`SCREEN_ORIENTATION_LOCKED`)
2. **Stream follows sensor rotation** continuously via `SensorRotationProvider`
3. These two are **decoupled** - one is locked, the other adapts

### Why Original Demo Didn't Have This Issue

The original demo:

- Probably didn't have persistent background streaming service
- Likely stopped/restarted stream on orientation change (lifecycle observer)
- May not have exposed rotation provider to users

Your app:

- Has background streaming service that continues during orientation changes
- Explicitly tracks rotation changes to apply to stream
- Allows stream to adapt to rotation while UI is locked

---

## Proposed Solutions

### Option 1: Lock to Sensor (Recommended)

**Match UI orientation to stream orientation by following sensor.**

**Implementation:**

```kotlin
// PreviewFragment.kt
private fun lockOrientation() {
    // Instead of SCREEN_ORIENTATION_LOCKED, follow the sensor
    // This way UI and stream both follow device physical orientation
    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
}

private fun unlockOrientation() {
    requireActivity().requestedOrientation = ApplicationConstants.supportedOrientation
}
```

**Pros:**

- ✅ UI and stream always match
- ✅ Natural behavior - device rotates, everything rotates
- ✅ Simple implementation - just change one constant

**Cons:**

- ⚠️ UI will rotate during streaming (may be disorienting for some users)
- ⚠️ May trigger layout recalculations during stream

**Best For:** Apps where user expects natural rotation behavior (most camera apps)

---

### Option 2: Lock Stream to UI Orientation

**Make stream follow UI orientation instead of sensor.**

**Implementation:**

```kotlin
// Option A: Use DisplayRotationProvider instead of SensorRotationProvider
// In CameraStreamerService.kt:212
val rotationProvider = DisplayRotationProvider(this@CameraStreamerService)

// Option B: Lock stream rotation to initial orientation when streaming starts
// In PreviewViewModel.kt or CameraStreamerService
private var lockedStreamRotation: Int? = null

fun startStreaming() {
    // Capture current UI rotation
    lockedStreamRotation = application.displayRotation

    // Set stream to this rotation and don't allow changes
    serviceStreamer?.setTargetRotation(lockedStreamRotation!!)

    // Remove rotation listener during streaming
    localRotationProvider?.removeListener(localRotationListener)
}

fun stopStreaming() {
    // Re-enable rotation tracking
    lockedStreamRotation = null
    localRotationProvider?.addListener(localRotationListener)
}
```

**Pros:**

- ✅ Stream matches UI orientation always
- ✅ No unexpected rotation during streaming
- ✅ Predictable behavior

**Cons:**

- ⚠️ User can't rotate device during streaming
- ⚠️ If user starts in wrong orientation, they're stuck
- ⚠️ More complex implementation

**Best For:** Apps where stream content should never change orientation mid-stream

---

### Option 3: Hybrid - Lock Both Initially, Allow Manual Override

**Lock both UI and stream to current orientation, but provide UI control to change.**

**Implementation:**

```kotlin
// Add a rotation button in the UI
binding.rotateButton.setOnClickListener {
    if (previewViewModel.isStreamingLiveData.value == true) {
        // Rotate both UI and stream together
        val currentRotation = requireActivity().requestedOrientation
        val newRotation = when (currentRotation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        requireActivity().requestedOrientation = newRotation

        // Convert to Surface rotation and apply to stream
        val surfaceRotation = when (newRotation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> Surface.ROTATION_0
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }

        viewLifecycleOwner.lifecycleScope.launch {
            previewViewModel.streamer?.setTargetRotation(surfaceRotation)
        }
    }
}
```

**UI Changes:**

- Add rotate button (only visible when streaming)
- Button manually toggles orientation

**Pros:**

- ✅ Full user control
- ✅ Predictable - only changes when user wants
- ✅ Good for users who want specific orientation

**Cons:**

- ⚠️ Requires UI changes
- ⚠️ Extra tap needed to rotate
- ⚠️ Most complex implementation

**Best For:** Professional streaming apps where orientation is a deliberate choice

---

### Option 4: Landscape-Only Streaming

**Force landscape mode for streaming, keep sensor for preview.**

**Implementation:**

```kotlin
// PreviewFragment.kt
private fun lockOrientation() {
    // Always force landscape when streaming
    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
}

private fun unlockOrientation() {
    requireActivity().requestedOrientation = ApplicationConstants.supportedOrientation
}
```

**Pros:**

- ✅ Simple - one orientation for streaming
- ✅ Predictable behavior
- ✅ Matches most live streaming apps (YouTube, Twitch)

**Cons:**

- ⚠️ No portrait streaming option
- ⚠️ May not suit all use cases

**Best For:** Apps focused on landscape streaming (gaming, tutorials, etc.)

---

## Comparison with Original StreamPack Demo

### What's Different in Your App

**Original Demo:**

```kotlin
// Probably stopped stream on orientation change via lifecycle observer
lifecycle.addObserver(StreamerViewModelLifeCycleObserver(streamer))

// onPause stops streaming
override fun onPause() {
    super.onPause()
    stopStream()  // <-- Original demo stops here
}
```

**Your App:**

```kotlin
// Removed lifecycle observer for background streaming
// lifecycle.addObserver(StreamerViewModelLifeCycleObserver(streamer))

override fun onPause() {
    super.onPause()
    // DO NOT stop streaming - service continues in background
    // stopStream()  // <-- You commented this out
}
```

**Impact:**

- Original demo: Stream stops on orientation change, restarts with new orientation
- Your app: Stream continues, but UI and stream can become mismatched

---

## Recommended Solution

### For LifeStreamer: Option 1 (Lock to Sensor) + Option 4 (Landscape Preference)

**Best approach for your app:**

```kotlin
// PreviewFragment.kt
private fun lockOrientation() {
    // Prefer landscape but follow sensor
    // This way users can rotate between landscape left/right
    // but typically stay in landscape
    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE

    // OR if you want full sensor freedom:
    // requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
}

private fun unlockOrientation() {
    requireActivity().requestedOrientation = ApplicationConstants.supportedOrientation
}
```

**Rationale:**

1. Most live streaming is landscape (YouTube, Twitch standard)
2. Allows user to flip between landscape left/right freely
3. Prevents portrait streaming (which is less common for life streaming)
4. Simple implementation
5. UI and stream always match

**Alternative (Full Sensor):**
If you want to support portrait streaming too, use `SCREEN_ORIENTATION_SENSOR` instead of `SCREEN_ORIENTATION_USER_LANDSCAPE`.

---

## Implementation Steps

### Step 1: Change Orientation Lock

**File:** `app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewFragment.kt`

```kotlin
private fun lockOrientation() {
    // Option A: Landscape-focused (recommended for live streaming)
    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE

    // Option B: Full sensor (if you want portrait support)
    // requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
}
```

### Step 2: Update ApplicationConstants (Optional)

**File:** `app/src/main/java/com/dimadesu/lifestreamer/ApplicationConstants.kt`

```kotlin
object ApplicationConstants {
    // Set default orientation for when not streaming
    val supportedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

    // Or if you prefer portrait by default:
    // val supportedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
```

### Step 3: Test Scenarios

1. **Portrait to Landscape:**

   - Start app in portrait
   - Start streaming
   - Rotate to landscape
   - Verify UI and stream both rotate

2. **Landscape to Portrait:**

   - Start app in landscape
   - Start streaming
   - Rotate to portrait
   - Verify UI and stream both rotate (if using SENSOR) or stay landscape (if using USER_LANDSCAPE)

3. **Background Streaming:**
   - Start streaming
   - Press home button
   - Rotate device
   - Return to app
   - Verify UI matches stream orientation

### Step 4: Update Comments/Documentation

Update comments to reflect the new behavior:

```kotlin
private fun lockOrientation() {
    /**
     * Lock orientation to landscape (or sensor) while streaming to maintain
     * consistency between UI and stream orientation. Unlike SCREEN_ORIENTATION_LOCKED,
     * this allows the device to rotate (e.g., landscape left <-> landscape right)
     * while ensuring the UI always matches what's being streamed.
     */
    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
}
```

---

## Additional Considerations

### 1. Preview vs Stream Orientation

Currently, your preview can show one orientation while stream is another. With the fix:

- Preview orientation = UI orientation = Stream orientation (all match)

### 2. RTMP Source with ExoPlayer

For RTMP sources where you're displaying another stream:

- ExoPlayer video orientation should match your UI
- May need to handle rotation metadata in the incoming RTMP stream

### 3. MediaProjection (Screen Recording)

When using MediaProjection to capture screen:

- Screen orientation = UI orientation
- No mismatch possible because you're capturing what's shown

### 4. Notification Behavior

When returning from notification:

- Ensure orientation is correctly restored
- Current implementation should handle this via `onResume()`

---

## Conclusion

**Problem:** UI orientation (locked) and stream orientation (sensor-based) are decoupled, causing confusion.

**Root Cause:** Using `SCREEN_ORIENTATION_LOCKED` locks UI to initial orientation, but `SensorRotationProvider` allows stream to rotate independently.

**Recommended Fix:** Change to `SCREEN_ORIENTATION_USER_LANDSCAPE` or `SCREEN_ORIENTATION_SENSOR` to allow UI and stream to rotate together.

**Implementation Effort:** Very low - just change one constant.

**Impact:** High - eliminates the confusing mismatch between UI and stream.

---

## Code References

### Key Files to Modify

1. **PreviewFragment.kt** (line 265-272)

   - Change `lockOrientation()` implementation

2. **ApplicationConstants.kt**
   - Review/update `supportedOrientation` if needed

### Key Files for Understanding

1. **CameraStreamerService.kt** (line 212-226)

   - Shows how rotation provider is set up in service

2. **PreviewViewModel.kt** (line 560-572)

   - Shows how rotation changes are queued/applied

3. **RotationRepository.kt**

   - Uses `SensorRotationProvider` for rotation tracking

4. **StreamPack/core/.../SensorRotationProvider.kt**

   - How sensor-based rotation detection works

5. **StreamPack/core/.../DisplayRotationProvider.kt**
   - Alternative: UI-based rotation detection

---

## Future Enhancements

1. **User Preference:**

   - Add setting: "Lock orientation during streaming" (portrait/landscape/sensor)
   - Store in DataStore preferences

2. **Smart Detection:**

   - Detect if user holds phone in landscape for >2 seconds
   - Auto-switch to landscape lock for that session

3. **Rotation Button:**

   - Add UI button to manually rotate during streaming
   - Useful for quick orientation fixes

4. **Orientation Indicator:**
   - Show icon indicating current stream orientation
   - Help users understand what's being streamed
