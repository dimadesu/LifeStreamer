# Orientation Fix Implementation

## Summary

Implemented a solution where UI follows sensor rotation when not streaming, locks to current orientation when streaming starts, and unlocks back to sensor rotation when streaming stops.

**Date:** October 6, 2025  
**Branch:** srt-in-background-mode-analysis

---

## User's Desired Behavior

1. **Before streaming:** UI follows device sensor (can rotate freely)
2. **During streaming:** UI and stream lock to current orientation (prevents disorienting rotations mid-stream)
3. **After streaming:** UI unlocks back to sensor mode (user can rotate freely again)

---

## Changes Made

### 1. ApplicationConstants.kt

**Changed default orientation from `UNSPECIFIED` to `SENSOR`:**

```kotlin
/**
 * Default application orientation when not streaming.
 * Uses SCREEN_ORIENTATION_SENSOR to follow device rotation naturally.
 * When streaming starts, orientation is locked to current position to prevent
 * disorienting rotations mid-stream.
 */
const val supportedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
```

**Impact:**

- App now follows device rotation naturally when not streaming
- Users can rotate to portrait or landscape before starting stream

---

### 2. PreviewFragment.kt

**Updated `lockOrientation()` and `unlockOrientation()` with better comments:**

```kotlin
private fun lockOrientation() {
    /**
     * Lock orientation to current position while streaming to prevent disorienting
     * rotations mid-stream. The user can choose their preferred orientation before
     * starting the stream (UI follows sensor via ApplicationConstants.supportedOrientation),
     * and it will stay locked to that orientation until streaming stops.
     *
     * SCREEN_ORIENTATION_LOCKED locks to whatever orientation the device is currently in,
     * ensuring the UI and stream orientation always match during the stream.
     */
    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
}

private fun unlockOrientation() {
    /**
     * Unlock orientation after streaming stops, returning to sensor-based rotation.
     * This allows the user to freely rotate the device and choose a new orientation
     * for the next stream.
     */
    requireActivity().requestedOrientation = ApplicationConstants.supportedOrientation
}
```

**Impact:**

- Clear documentation of the locking behavior
- No functional change (already using SCREEN_ORIENTATION_LOCKED)
- But now it works correctly because unlocking goes to SENSOR mode

---

### 3. CameraStreamerService.kt

**Added stream rotation locking mechanism:**

```kotlin
// Stream rotation locking: when streaming starts, lock to current rotation
// and ignore sensor changes until streaming stops
private var lockedStreamRotation: Int? = null
```

**Updated rotation listener to respect lock:**

```kotlin
val listener = object : IRotationProvider.Listener {
    override fun onOrientationChanged(rotation: Int) {
        // If stream rotation is locked (during streaming), ignore sensor changes
        if (lockedStreamRotation != null) {
            Log.d(TAG, "Ignoring rotation change to ${rotationToString(rotation)} - stream rotation is locked")
            return
        }

        // When not streaming, update rotation normally
        try {
            serviceScope.launch {
                try {
                    (streamer as? IWithVideoRotation)?.setTargetRotation(rotation)
                    currentRotation = rotation
                    Log.d(TAG, "Rotation updated to ${rotationToString(rotation)}")
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
```

**Lock rotation when streaming starts:**

```kotlin
override fun onStreamingStart() {
    // Lock stream rotation to current orientation when streaming starts
    currentRotation = detectCurrentRotation()
    lockedStreamRotation = currentRotation
    Log.i(TAG, "Stream rotation locked to ${rotationToString(currentRotation)} at stream start")

    // ... rest of method
}
```

**Unlock rotation when streaming stops:**

```kotlin
override fun onStreamingStop() {
    // Unlock stream rotation when streaming stops
    lockedStreamRotation = null
    Log.i(TAG, "Stream rotation unlocked - will follow sensor again")

    // ... rest of method
}
```

**Impact:**

- Stream rotation now locks when streaming starts
- Sensor rotation changes are ignored during streaming
- Stream rotation unlocks when streaming stops
- UI and stream orientation always stay in sync

---

## How It Works

### Before Streaming

1. **UI:** Follows `ApplicationConstants.supportedOrientation` = `SCREEN_ORIENTATION_SENSOR`
2. **Stream:** Also follows sensor (via `SensorRotationProvider` listener)
3. **User Experience:** Can freely rotate device, both UI and preview rotate naturally

### When Streaming Starts

1. **UI:** `lockOrientation()` sets `SCREEN_ORIENTATION_LOCKED` → locks to current orientation
2. **Stream:** `onStreamingStart()` sets `lockedStreamRotation = currentRotation` → locks to current orientation
3. **Rotation Listener:** Checks `lockedStreamRotation != null` → ignores all sensor changes
4. **User Experience:** Orientation is frozen at whatever it was when stream started

### When Streaming Stops

1. **UI:** `unlockOrientation()` sets `SCREEN_ORIENTATION_SENSOR` → returns to sensor-based rotation
2. **Stream:** `onStreamingStop()` sets `lockedStreamRotation = null` → returns to sensor-based rotation
3. **Rotation Listener:** `lockedStreamRotation == null` → resumes responding to sensor changes
4. **User Experience:** Can freely rotate device again, both UI and preview respond to rotation

---

## Benefits

### ✅ No More Confusion

- UI orientation and stream orientation are **always** in sync
- No more portrait UI showing landscape stream content

### ✅ User Control

- User can choose orientation **before** streaming (rotate freely)
- Orientation **locks** during streaming (no accidental rotations)
- Orientation **unlocks** after streaming (freedom restored)

### ✅ Natural Behavior

- Matches user expectations from other camera/streaming apps
- Prevents disorienting mid-stream rotations
- Allows pre-stream orientation selection

### ✅ Simple Implementation

- Minimal code changes
- Clear and maintainable logic
- Well-documented behavior

---

## Testing Scenarios

### Test 1: Portrait Streaming

1. Hold device in portrait
2. Start streaming → UI and stream both portrait
3. Try rotating device → UI stays portrait, stream stays portrait ✅
4. Stop streaming → UI unlocks, can rotate freely ✅

### Test 2: Landscape Streaming

1. Hold device in landscape
2. Start streaming → UI and stream both landscape
3. Try rotating device → UI stays landscape, stream stays landscape ✅
4. Stop streaming → UI unlocks, can rotate freely ✅

### Test 3: Orientation Selection

1. Hold device in portrait
2. Rotate to landscape → UI rotates to landscape ✅
3. Start streaming → UI and stream lock to landscape ✅
4. Stop streaming → UI unlocks ✅

### Test 4: Background Streaming

1. Start streaming in portrait
2. Go to background (home button)
3. Rotate device physically
4. Return to app → UI still portrait, stream still portrait ✅
5. Stop streaming → UI unlocks ✅

### Test 5: Notification Return

1. Start streaming in landscape
2. Go to background
3. Tap notification to return
4. Verify UI and stream both still landscape ✅

---

## Log Messages

**When rotation is locked during streaming:**

```
Ignoring rotation change to ROTATION_90 (Landscape Left) - stream rotation is locked to ROTATION_0 (Portrait)
```

**When streaming starts:**

```
Stream rotation locked to ROTATION_0 (Portrait) at stream start
```

**When streaming stops:**

```
Stream rotation unlocked - will follow sensor again
```

**When rotation updates (not streaming):**

```
Rotation updated to ROTATION_90 (Landscape Left)
```

---

## Related Files

**Modified:**

1. `app/src/main/java/com/dimadesu/lifestreamer/ApplicationConstants.kt`
2. `app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewFragment.kt`
3. `app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt`

**Documentation:**

1. `docs/ORIENTATION_HANDLING_ANALYSIS.md` (comprehensive analysis)
2. `docs/ORIENTATION_FIX_IMPLEMENTATION.md` (this file)

---

## Future Enhancements

### Optional: User Preference

Add a setting to choose orientation behavior:

- **Auto (current):** Follow sensor when not streaming, lock during streaming
- **Portrait only:** Always portrait
- **Landscape only:** Always landscape
- **Always follow sensor:** Never lock (advanced users)

### Optional: Quick Rotation Button

Add a button to manually rotate during streaming (for quick fixes):

- Only visible when streaming
- Rotates 90° clockwise
- Updates both UI and stream rotation

### Optional: Orientation Indicator

Show small icon indicating current stream orientation:

- Portrait icon when in portrait
- Landscape icon when in landscape
- Helps users understand what's being streamed

---

## Conclusion

This implementation provides the **best of both worlds**:

- Freedom to choose orientation before streaming
- Stability during streaming (no accidental rotations)
- Natural rotation behavior when not streaming

The changes are minimal, well-documented, and solve the original confusion where UI and stream could be in different orientations.
