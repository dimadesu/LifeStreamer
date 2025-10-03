# RTMP Status Messages and Automatic Retry

## Overview

This document describes the implementation of user-facing RTMP connection status messages and automatic retry logic added to improve the user experience when switching to RTMP sources.

## Problem Statement

Previously, when switching to an RTMP source:

- No feedback was provided to the user during connection attempts
- A single connection attempt was made with 3-second timeout
- If connection failed, user had to manually retry by toggling the source button
- User couldn't tell if the app was frozen or actively trying to connect

## Solution

Implemented automatic retry with visible status messages:

1. **First attempt**: Display "Playing RTMP" to inform user of initial connection
2. **Retry attempts**: Display "Trying to play RTMP" every 5 seconds on retry
3. **Success**: Clear status message and show RTMP video
4. **Continuous retry**: Keep retrying indefinitely until successful or user switches away

## Changes Made

### 1. RtmpSourceSwitchHelper.kt

**Location**: `app/src/main/java/com/dimadesu/lifestreamer/ui/main/RtmpSourceSwitchHelper.kt`

**Key Changes**:

- Added `postRtmpStatus: (String?) -> Unit` parameter to `switchToRtmpSource()`
- Wrapped connection logic in infinite retry loop with 5-second delay
- First attempt shows "Playing RTMP"
- Subsequent attempts show "Trying to play RTMP"
- Clears status message (`postRtmpStatus(null)`) on successful connection
- Launches retry loop in background coroutine scope
- Returns `true` immediately to indicate process has started

**Code Structure**:

```kotlin
suspend fun switchToRtmpSource(..., postRtmpStatus: (String?) -> Unit): Boolean {
    var attemptCount = 0

    CoroutineScope(Dispatchers.Default).launch {
        while (attemptCount < Int.MAX_VALUE) {
            attemptCount++
            val isFirstAttempt = attemptCount == 1

            // Show status
            if (isFirstAttempt) {
                postRtmpStatus("Playing RTMP")
            } else {
                postRtmpStatus("Trying to play RTMP")
            }

            // Connection logic...
            // On success: postRtmpStatus(null) and return@launch
            // On failure: delay(5000) and continue
        }
    }

    return true
}
```

### 2. PreviewViewModel.kt

**Location**: `app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt`

**Key Changes**:

- Added `_rtmpStatusLiveData: MutableLiveData<String?>` around line 186
- Exposed as `val rtmpStatusLiveData: LiveData<String?>`
- Updated both `toggleVideoSource()` call sites to pass status callback:
  ```kotlin
  postRtmpStatus = { msg -> _rtmpStatusLiveData.postValue(msg) }
  ```
- Clears status when switching back to camera: `_rtmpStatusLiveData.postValue(null)`

### 3. main_fragment.xml

**Location**: `app/src/main/res/layout/main_fragment.xml`

**Key Changes**:

- Added new `TextView` with id `rtmpStatusText`
- Positioned in center of screen using ConstraintLayout constraints
- Styling:
  - White text, 20sp, bold
  - Semi-transparent black background (#80000000)
  - 16dp padding for readability
- Data binding: `android:text='@{viewmodel.rtmpStatusLiveData}'`
- Visibility: `app:goneUnless='@{viewmodel.rtmpStatusLiveData != null}'`
  - Shows only when status message exists
  - Hidden when null (normal operation or camera source)

## User Experience Flow

### Success Case (RTMP server online):

1. User taps "Switch Source" button
2. UI immediately shows "Playing RTMP" centered on screen
3. Connection succeeds within 3 seconds
4. Status message disappears
5. RTMP video stream is displayed

### Retry Case (RTMP server offline/unreachable):

1. User taps "Switch Source" button
2. UI shows "Playing RTMP" centered on screen
3. After 3-second timeout, first attempt fails
4. Fallback bitmap is displayed (prevents frozen UI)
5. After 5-second delay, UI shows "Trying to play RTMP"
6. Connection attempt #2 (another 3-second timeout)
7. If fails, waits 5 seconds and shows "Trying to play RTMP" again
8. Continues retrying every 5 seconds indefinitely
9. When server becomes available, connection succeeds and status clears
10. User can manually switch back to camera at any time to stop retries

### Switch Away During Retry:

1. While retry loop is active and showing "Trying to play RTMP"
2. User taps "Switch Source" to go back to camera
3. Status message immediately clears
4. Camera source is restored
5. Retry loop continues in background but user isn't affected

## Technical Details

### Coroutine Scope

- Retry loop runs in `CoroutineScope(Dispatchers.Default)`
- Decoupled from caller's lifecycle
- Continues until success or app termination
- No explicit cancellation mechanism (relies on success condition)

### Timing

- **Initial connection timeout**: 3 seconds (unchanged from previous optimization)
- **Retry interval**: 5 seconds between attempts
- **Total cycle time**: ~8 seconds per retry (3s connection + 5s delay)

### Error Handling

- First attempt failures show error dialog (via `postError`)
- Subsequent retry failures are silent (only status message changes)
- Prevents spam of error dialogs during extended retries
- ExoPlayer instances properly released on failure

### Audio Handling

- Microphone audio attached immediately on RTMP success
- Background task attempts to upgrade to MediaProjection audio
- 10-second window to acquire MediaProjection
- Falls back to microphone if MediaProjection unavailable

## Benefits

1. **User Feedback**: Clear indication of connection status and retry attempts
2. **Automatic Recovery**: No manual intervention needed if RTMP server is temporarily down
3. **Non-Blocking**: Retry logic runs in background, doesn't freeze UI
4. **Graceful Degradation**: Falls back to bitmap immediately to keep stream alive
5. **Persistent**: Continues retrying indefinitely, suitable for temporary network issues
6. **Clean UI**: Status message only shows when relevant, auto-hides on success

## Testing Scenarios

### Test 1: Successful Connection

1. Ensure RTMP server is running and reachable
2. Tap "Switch Source" button
3. **Expected**: "Playing RTMP" appears briefly, then RTMP video displays

### Test 2: Server Offline

1. Ensure RTMP server is stopped/unreachable
2. Tap "Switch Source" button
3. **Expected**:
   - "Playing RTMP" for ~3 seconds
   - Bitmap appears
   - "Trying to play RTMP" every ~8 seconds
   - Continuous retry loop

### Test 3: Server Recovery

1. Start with server offline scenario (Test 2)
2. Wait for a few retry attempts
3. Start RTMP server
4. **Expected**:
   - Next retry attempt succeeds
   - Status message clears
   - RTMP video displays

### Test 4: Switch Away During Retry

1. Start with server offline scenario (Test 2)
2. While "Trying to play RTMP" is showing
3. Tap "Switch Source" to return to camera
4. **Expected**:
   - Status message disappears
   - Camera preview returns
   - No more status messages

### Test 5: Mid-Stream Connection

1. Start streaming with camera source
2. Switch to RTMP source during active stream
3. **Expected**:
   - Stream continues (no interruption)
   - Status messages appear as per Test 1 or 2
   - Video source transitions without stopping stream

## Performance Considerations

- **Memory**: One background coroutine per retry session (negligible)
- **CPU**: Retry loop uses `delay()` (non-blocking, no busy-waiting)
- **Network**: Connection attempts every ~8 seconds (reasonable for temporary outages)
- **Battery**: Minimal impact due to coroutine efficiency and sparse attempts

## Future Enhancements (Optional)

1. **Exponential Backoff**: Increase delay between retries over time (e.g., 5s → 10s → 20s)
2. **Max Retry Count**: Stop retrying after N attempts and show persistent error
3. **User Control**: Add "Stop Retrying" button in status message
4. **Notification**: Show notification when connection succeeds after retries
5. **Analytics**: Track retry attempts and success rates for debugging

## Related Documentation

- `DOUBLE_ERROR_DIALOG_FIX.md` - Error dialog deduplication fixes
- `BUTTON_STATE_IMPROVEMENTS.md` - Button state management simplification
- Git branch: `lower-rtmp-timeout` (previous work on timeout reduction)
