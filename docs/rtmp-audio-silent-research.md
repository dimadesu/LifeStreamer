# Investigation: MediaProjection Audio Loss on RTMP Stream Stop/Restart

## Summary
When an RTMP stream stops or restarts, the published stream ends up without audio. Audio is captured via MediaProjection's AudioPlaybackCapture API, filtering by the app's UID to capture ExoPlayer's RTMP audio output.

## Architecture
- ExoPlayer plays RTMP stream (volume=0, video rendered to encoder surface)
- MediaProjection AudioPlaybackCapture captures ExoPlayer's audio output by UID filter
- StreamPack library encodes + publishes audio/video over SRT
- On RTMP disconnect: video switches to bitmap fallback, audio stays on MediaProjection or switches to mic
- Two disconnect handling paths: "restart" (stop+restart SRT stream) and "hot-swap" (swap video only)

## Root Cause Chain

**RTMP stream stops → ExoPlayer released → MediaProjection token becomes invalid (service lifecycle or system reclaim) → `AudioRecord.read()` returns errors → errors silently caught in pull-push loop → audio permanently drops to zero frames → no detection, no recovery, no user notification.**

## Findings

### Issue 1: AudioRecord read errors silently swallowed (HIGH)
- `AudioRecordSource.fillAudioFrame()` throws on `AudioRecord.read()` errors
- `RawFramePullPush` catch block logs the error but continues the loop producing no frames
- Audio silently drops to nothing — no recovery, no notification to user
- Files: `AudioRecordSource.kt:159-173`, `RawFramePullPush.kt:86-93`

### Issue 2: No `MediaProjection.Callback` registered (HIGH)
- Android can invalidate MediaProjection tokens at any time (service kill, user revoke, system)
- The app never registers `MediaProjection.Callback.onStop()` to detect token death
- When token dies, `AudioRecord.read()` returns errors → Issue 1 kicks in → silent audio loss
- Files: `MediaProjectionAudioSource.kt`, `MediaProjectionHelper.kt`, `MediaProjectionService.kt`

### Issue 3: `clearMediaProjection()` is never called (MEDIUM)
- Both `MediaProjectionHelper.clearMediaProjection()` and `MediaProjectionService.clearMediaProjection()` exist
- Neither is ever called anywhere in the codebase
- Stale or expired tokens can accumulate / be reused
- Files: `MediaProjectionHelper.kt:157`, `MediaProjectionService.kt:122`

### Issue 4: Race between MediaProjection audio restore and RTMP retry (MEDIUM)
- In `attemptReconnection()`, MediaProjection audio is restored at line ~2265
- `ensureRtmpRetryRunning()` is called at line ~2289
- When RTMP retry succeeds, `switchToRtmpSource` may try to set audio source AGAIN
- Two concurrent attempts to set audio source can race
- File: `PreviewViewModel.kt:2265-2289`

### Issue 5: Restart path recreates AudioRecord before ExoPlayer exists (LOW-MED)
- `handleRtmpDisconnectionWithRestart`: creates new MediaProjection audio source at step 5
- At this point old ExoPlayer is released, new one doesn't exist yet
- AudioRecord captures silence (no audio from app UID)
- When new ExoPlayer starts later (retry loop), AudioRecord should capture it — but no verification
- File: `PreviewViewModel.kt:2800-2830`

### Issue 6: No AudioRecord state monitoring (LOW-MED)
- After AudioRecord is created and started, there's no periodic health check
- If AudioRecord goes to `BAD_VALUE` or `DEAD_OBJECT` state, it's detected only on next `read()`
- Which then triggers Issue 1 (silently swallowed)

## Key Files
- `app/src/main/java/com/dimadesu/lifestreamer/rtmp/audio/MediaProjectionAudioSource.kt`
- `app/src/main/java/com/dimadesu/lifestreamer/rtmp/audio/AudioRecordSource.kt`
- `app/src/main/java/com/dimadesu/lifestreamer/rtmp/audio/MediaProjectionHelper.kt`
- `app/src/main/java/com/dimadesu/lifestreamer/rtmp/audio/MediaProjectionService.kt`
- `app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt`
- `app/src/main/java/com/dimadesu/lifestreamer/ui/main/RtmpSourceSwitchHelper.kt`
- `StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/processing/RawFramePullPush.kt`
- `StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/pipelines/inputs/AudioInput.kt`

## Further Considerations

1. **Add `MediaProjection.Callback`** to detect token death and trigger audio source recreation — most impactful fix.
2. **Propagate fatal audio errors upstream** from `RawFramePullPush` (e.g., after N consecutive failures, stop the stream or trigger reconnection).
3. **Validate audio capture is working** after stream restart (e.g., check AudioRecord state, verify frames are being produced).