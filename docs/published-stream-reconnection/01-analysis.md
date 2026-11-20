Here is an analysis of how reconnection and pipeline lifecycle currently work across **LifeStreamer** and your **StreamPack** fork, why the current behavior causes issues for simultaneous local recording and RTMP ingest servers, and a proposed approach for improving both.

---

## 1. How Reconnection Works Today

### 1.1 Inside StreamPack: Any Endpoint Drop Stops the Entire Pipeline
In [CameraStreamerService.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt#L63-L71), the service creates a `SingleStreamer` (which wraps one `StreamerPipeline` and one `EncodingPipelineOutput`).

When a network error occurs:
1. **Endpoint emits `isOpenFlow = false` and `ClosedException`**:
   - **SRT**: In [SrtSink.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/srt/src/main/java/io/github/thibaultbee/streampack/ext/srt/elements/endpoints/composites/sinks/SrtSink.kt#L91-L94) and [SrtSink.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/srt/src/main/java/io/github/thibaultbee/streampack/ext/srt/elements/endpoints/composites/sinks/SrtSink.kt#L148-L178), socket completion sets `_isOpenFlow = false`, and the next `write()` throws `ClosedException` directly up into `EncodingPipelineOutput`.
   - **RTMP**: In [RtmpEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/rtmp/src/main/java/io/github/thibaultbee/streampack/ext/rtmp/elements/endpoints/RtmpEndpoint.kt#L206-L226), `socketContext.invokeOnCompletion` sets `_isOpenFlow = false` and emits `ClosedException` on `_throwableFlow`.
2. **`EncodingPipelineOutput` automatically stops itself**:
   In [EncodingPipelineOutput.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/pipelines/outputs/encoding/EncodingPipelineOutput.kt#L345-L349), there is a built-in observer:
   ```kotlin
   endpointInternal.isOpenFlow.filter { !it }.drop(1).collect { isOpen ->
       coroutineScope.launch { stopStream() }
   }
   ```
   And any exception thrown from `endpointInternal.write()` calls `onInternalError(t)` ([EncodingPipelineOutput.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/pipelines/outputs/encoding/EncodingPipelineOutput.kt#L252-L263)), which also launches `stopStream()`.
3. **`EncodingPipelineOutput.stopStream()` tears down encoders and sources**:
   - In [EncodingPipelineOutput.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/pipelines/outputs/encoding/EncodingPipelineOutput.kt#L795-L887), `stopStreamUnsafe()` invokes `streamEventListener?.onStopStream()`.
   - In [StreamerPipeline.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/pipelines/StreamerPipeline.kt#L458-L461) and [L848-L903](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/pipelines/StreamerPipeline.kt#L848-L903), `stopStreamInputsIfNeededUnsafe()` checks if any *other* outputs in the pipeline are still streaming (`getNumOfAudioStreamingOutputSafe(output) == 0`, `getNumOfVideoStreamingOutputSafe(output) == 0`). Because `SingleStreamer` only has one output, **`StreamerPipeline` stops `_audioInput` and `_videoInput`** and emits `isStreamingFlow = false`.
   - `EncodingPipelineOutput.stopStreamElements()` stops the endpoint, stops and resets both `MediaCodecEncoder`s ([MediaCodecEncoder.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/encoders/mediacodec/MediaCodecEncoder.kt#L194-L206)), destroys the video encoder's input `Surface`, and creates a brand-new `Surface` via `createInputSurface()` ([MediaCodecEncoder.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/encoders/mediacodec/MediaCodecEncoder.kt#L532-L542)), which forces `VideoInput` (`ISurfaceProcessor`) to detach and re-attach the encoder surface.

### 1.2 Inside LifeStreamer: Full Streamer Teardown & Restart
In [PreviewViewModel.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L1424-L1506), disconnection is caught by two observers (`throwableFlow` filtering `isClosedException`, and `isStreamingFlow` transitioning from `true` to `false`), which call `handleDisconnection()` ([PreviewViewModel.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L2330-L2444)):
1. Calls `currentStreamer.stopStream()` (which calls `pipeline.stopStream()`, stopping all inputs and all outputs) and `currentStreamer.close()`.
2. Detaches the bitrate regulator (`bitrateRegulatorControllerFactory = null`).
3. Meanwhile, because `isStreamingFlow` dropped to `false`, `CameraStreamerService.onStreamingStop()` ([CameraStreamerService.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt#L586-L621)) **releases both `wakeLock` and `networkWakeLock`** and resets `streamingStartTime = null` (losing total stream uptime, and leaving the app vulnerable to background CPU/network throttling during the 5-second wait before `attemptReconnection()`).
4. After 5 seconds, `attemptReconnection()` calls `doStartStream()` -> `startServiceStreaming()` ([PreviewViewModel.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L777-L915)), which re-opens the endpoint, re-applies target rotation, restarts encoders and sources, resets bitrate, re-attaches the bitrate regulator, and re-triggers Bluetooth SCO activation.

---

## 2. Simultaneous Local Recording + Live Streaming Analysis

StreamPack provides two ways to record and live-stream simultaneously ([LiveAndRecordSimultaneously.md](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/docs/use_cases/LiveAndRecordSimultaneously.md)):
1. **`DualEndpoint`** (Single `SingleStreamer` / 1 Video Encoder + 1 Audio Encoder -> `DualEndpoint(mainEndpoint = LocalFile, secondEndpoint = RemoteStream)`).
2. **`DualStreamer` / `StreamerPipeline` with 2 `EncodingPipelineOutput`s** (2 independent `EncodingPipelineOutput`s sharing the same `AudioInput` and `VideoInput`).

Let's examine how reconnection behaves with each option and what pitfalls exist.

### Option A: `DualEndpoint` (Single Encoder, Shared Output)
In `DualEndpoint` ([DualEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/DualEndpoint.kt), extending [CombineEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/CombineEndpoint.kt)):
- `CombineEndpoint.isOpenFlow` stays `true` as long as **any** child endpoint is open (`emit(isOpens.any { it })`), and `CombineEndpoint.write()` swallows write exceptions from one endpoint as long as at least one other endpoint remains open ([CombineEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/CombineEndpoint.kt#L246-L256)).
- That means when `mainEndpoint` (local file) is open and `secondEndpoint` (remote stream) disconnects, `EncodingPipelineOutput` does **not** automatically call `stopStream()`.

**However, there are 4 critical traps in StreamPack's `DualEndpoint` / `CombineEndpoint` as currently written:**

1. **`addStreams()` is never called when reconnecting `secondEndpoint`**:
   - `CombineEndpoint.addStreams(streams)` ([CombineEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/CombineEndpoint.kt#L136-L146)) is only called **once** by `EncodingPipelineOutput.startStreamUnsafe()` when the pipeline first starts.
   - If you call `dualEndpoint.stopStreamSecond()` on disconnect ([DualEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/DualEndpoint.kt#L104-L109)), it calls `secondEndpoint.stopStream()` (which clears `FlvTagBuilder` streams in RTMP and clears `TsMuxer` streams in SRT) **and** removes `secondEndpoint`'s entries from `endpointsToStreamIdsMap`.
   - When you later call `dualEndpoint.openSecond(descriptor)` + `dualEndpoint.startStreamSecond()`, `secondEndpoint.addStreams()` is **never called**:
     - For **SRT** ([CompositeEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/composites/CompositeEndpoint.kt#L99-L103)): `TsMuxer` has no streams registered (`muxer.streamConfigs` is empty).
     - For **RTMP** ([RtmpEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/rtmp/src/main/java/io/github/thibaultbee/streampack/ext/rtmp/elements/endpoints/RtmpEndpoint.kt#L278-L290)): `flvTagBuilder.metadata` has empty audio/video configs.
     - On the next frame in [CombineEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/CombineEndpoint.kt#L232), `endpointsToStreamIdsMap[Pair(endpoint, streamPid)]!!` throws a **`NullPointerException`**.
   - Conversely, if you only call `closeSecond()` *without* `stopStreamSecond()` (as was experimented with on `v2/reconnection-dont-stop-start-pipeline`):
     - For **RTMP**, `RtmpEndpoint.close()` does **not** reset `flvTagBuilder` or `startUpTimestamp`! As a result, `AudioFlvStream.sentSequenceStart` and `VideoFlvStream.sentSequenceStart` ([FlvStream.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/flv/src/main/java/io/github/thibaultbee/streampack/ext/flv/elements/endpoints/composites/muxer/utils/FlvStream.kt#L30-L71)) remain `true` from the previous connection. Upon reconnecting, **`RtmpEndpoint` never sends the AVC/HEVC Sequence Header (SPS/PPS) or AAC Sequence Header (`AudioSpecificConfig`) to the new RTMP session**, rendering the reconnected RTMP stream undecodable!
2. **Race condition between `openSecond()` and `startStreamSecond()` in `CombineEndpoint.write()`**:
   - [CombineEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/CombineEndpoint.kt#L224) writes frames to every endpoint where `it.isOpenFlow.value == true`.
   - Because the encoders are actively running for the file recording, as soon as `openSecond()` completes (`isOpenFlow` becomes `true`), `CombineEndpoint.write()` starts pushing frames to `secondEndpoint` *before* `startStreamSecond()` has completed!
   - For **SRT** ([SrtSink.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/srt/src/main/java/io/github/thibaultbee/streampack/ext/srt/elements/endpoints/composites/sinks/SrtSink.kt#L119)), `_isOpenFlow.emit(true)` happens in `open()`, before `startStream()` configures `MAXBW`/`INPUTBW` and before `TsMuxer` is ready if streams are re-added.
3. **Cannot start if the remote server is initially unreachable**:
   - In `DualEndpoint`, if the user hits Start while recording + streaming and the remote server is temporarily unreachable, `openSecond()` throws, or if only `mainEndpoint` is opened, `addStreams` in `CombineEndpoint` must still register streams for `secondEndpoint` before `secondEndpoint` is even open.
4. **Adaptive Bitrate (ABR) degrades the local file recording**:
   - Because `DualEndpoint` uses a **single video encoder** for both file recording and live streaming, when network congestion occurs (right before a disconnect), the SRT/RTMP bitrate regulator lowers `videoEncoder.bitrate` (e.g., from 6 Mbps down to 300–500 kbps). Your local disk recording gets recorded at that degraded 300 kbps bitrate as well.

### Option B: `DualStreamer` / `StreamerPipeline` with Two `EncodingPipelineOutput`s
Let's look at how `StreamerPipeline` ([StreamerPipeline.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/pipelines/StreamerPipeline.kt)) and `DualStreamerImpl` ([DualStreamerImpl.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/streamers/dual/DualStreamerImpl.kt)) work:
- One `StreamerPipeline` holds the shared `AudioInput` and `VideoInput` (camera / UVC / RTMP / screen + `ISurfaceProcessor`).
- It has two independent `EncodingPipelineOutput` instances (e.g., `liveOutput` and `recordOutput`):
  - Look at `StreamerPipeline.stopStreamInputsIfNeededUnsafe(output)` ([StreamerPipeline.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/pipelines/StreamerPipeline.kt#L848-L903)):
    When `liveOutput` disconnects and calls `liveOutput.stopStream()`, `StreamerPipeline` checks `getNumOfAudioStreamingOutputSafe(liveOutput)` and `getNumOfVideoStreamingOutputSafe(liveOutput)`. Because `recordOutput` is still streaming (`count == 1`), **`StreamerPipeline` does NOT stop `AudioInput` or `VideoInput`**, and `pipeline.isStreamingFlow` stays `true`!
  - `recordOutput` continues encoding and muxing to disk with zero interruption and at a constant high bitrate, completely isolated from `liveOutput`'s adaptive bitrate regulator and network state.
  - Meanwhile, `liveOutput` can independently `close()`, wait 5s, `open(remoteDescriptor)`, and `startStream()` without touching `recordOutput` or stopping the camera/microphone sources.

---

## 3. RTMP-Specific Issues: Stopping, `deleteStream`, Sequence Headers, and Timestamps

You noted:
> *"Additionally in particular for RTMP I think I need to be more carefully with stopping and restarting the stream as stopping the stream can signal to ingest server that it is the end of transmission."*

You are spot on. Looking at [RtmpEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/rtmp/src/main/java/io/github/thibaultbee/streampack/ext/rtmp/elements/endpoints/RtmpEndpoint.kt#L292-L315):

```kotlin
override suspend fun stopStream() {
    isStarted = false
    try {
        mutex.withLock {
            if (rtmpClient?.isClosed == false) {
                val deleted = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                    rtmpClient?.deleteStream()
                    true
                }
                if (deleted == null) {
                    Logger.w(TAG, "RTMP deleteStream timeout after 3 seconds")
                }
            }
        }
    } catch (t: Throwable) {
...
```

Here are the exact issues with how RTMP is currently stopped and restarted:

### 3.1 `RtmpEndpoint.stopStream()` Sends `deleteStream` (End of Transmission)
In the RTMP protocol, sending `deleteStream` (and/or `FCUnpublish`) is an **explicit "broadcast finished" command**.
- **Ingest server behavior (YouTube, Twitch, Kick, Restream, SRS, etc.)**: Most ingest servers have a disconnect-protection / resume window when a TCP socket drops unexpectedly (EOF / TCP RST / timeout), keeping the broadcast alive while waiting for the encoder to reconnect. However, if the client sends `deleteStream`, the ingest server treats it as an **intentional stop by the user** and immediately ends the live broadcast or closes out the VOD.
- **When does LifeStreamer accidentally send `deleteStream` during a live session?**
  1. **When an inbound RTMP Video Source disconnects (`handleRtmpDisconnectionWithRestart`)**:
     In [PreviewViewModel.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L3166-L3332), when LifeStreamer is playing an inbound RTMP source (e.g., from a drone/camera) and that *input* disconnects, if `rtmpSourceRestartOnDisconnect` is enabled (the default), `handleRtmpDisconnectionWithRestart()` calls `currentStreamer.stopStream()` and `currentStreamer.close()` on the **outbound** published stream, switches to the fallback bitmap, and restarts the outbound stream!
     - That comment at line 3163 says: *"This resets the SRT connection to clear any timestamp discontinuity issues. Recommended approach - fixes OBS media source playback issues."*
     - While restarting an outbound **SRT** connection to OBS may have been a workaround for timestamp discontinuities, doing that when the outbound stream is **RTMP** (or while **local file recording** is running!) sends `deleteStream` to a healthy RTMP ingest server—ending the broadcast—and stops local recording!
     - Even more importantly: in the current codebase, **all video sources feed

Error: The stream was interrupted. Please continue the task you were working on. (Error ID: 125e0500-9795-434b-a0ec-5061ec02fc98-72)

*(Continuing from Section 3.1 above)*

- Even more importantly: in the current codebase, **all video sources feed through `ISurfaceProcessor` (`DefaultSurfaceProcessor`) and `RtmpAudioBridge` / `ExoPlayerAudioSource`**, which generate continuous monotonic `System.nanoTime()` timestamps regardless of input source switches. Restarting the outbound published stream on an inbound RTMP source disconnect should **never** happen when publishing to an **RTMP** ingest server (or when local file recording is active).
  2. **When an outbound RTMP connection fails or stalls without the Ktor socket immediately reporting `isClosed = true`**:
     If a connection error or write timeout triggers `stopStream()` while `rtmpClient?.isClosed == false`, `RtmpEndpoint.stopStream()` attempts to send `rtmpClient.deleteStream()` and blocks for up to 3 seconds waiting for a response on a dying socket.
     - During an **unexpected disconnect / reconnection**, we should **never** send `deleteStream()`—we should only close the socket (`rtmpClient?.close()`) and reset internal FLV muxer state. `deleteStream()` should only be sent on an explicit, user-initiated manual stop.

### 3.2 RTMP Sequence Headers & Inter-Frames When Reconnecting Without Stopping the Encoder
If we reconnect `RtmpEndpoint` while keeping the `MediaCodec` video encoder running (which happens in `DualEndpoint`, or if we reconnect an endpoint without stopping/resetting the encoders):
- Look at `VideoFlvStream.createImpl(frame)` in [FlvStream.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/flv/src/main/java/io/github/thibaultbee/streampack/ext/flv/elements/endpoints/composites/muxer/utils/FlvStream.kt#L63-L71):
  ```kotlin
  override fun createImpl(frame: Frame): List<FLVData> {
      var withSequenceStart = false
      if ((!sentSequenceStart) && (frame.isKeyFrame)) {
          // Send config
          withSequenceStart = true
          sentSequenceStart = true
      }
      return frameFactory.create(frame, withSequenceStart)
  }
  ```
- When `RtmpEndpoint` reconnects while the video encoder is already running, the first frames arriving from the encoder before `requestKeyFrame()` takes effect are **inter-frames (P-frames, `isKeyFrame == false`)**.
- Because `VideoFlvStream.createImpl()` still calls `frameFactory.create(frame, withSequenceStart = false)` when `sentSequenceStart == false`, **it sends P-frames to the RTMP ingest server before the AVC/HEVC Sequence Header (`AVCDecoderConfigurationRecord` / SPS+PPS) has been sent!**
- Most RTMP ingest servers either drop the connection, log decoder errors, or produce corrupted video if `AVCPacketType = 1` (NALU) arrives before `AVCPacketType = 0` (Sequence Header).
- **Fix needed in `FlvStream.kt`**: If `!sentSequenceStart && !frame.isKeyFrame`, `VideoFlvStream.createImpl()` must return `emptyList()` (dropping trailing P-frames until the first keyframe arrives and sends the sequence header).
- Note that `TsMuxer` (used for **SRT/SRTLA**) has a similar behavior: in [TsMuxer.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/composites/muxers/ts/TsMuxer.kt#L89-L106), SPS/PPS and forced PAT/PMT are only prepended on `frame.isKeyFrame`, so calling `videoEncoder?.requestKeyFrame()` right after `startStream()` on reconnect is essential for SRT too.

### 3.3 RTMP Timestamps (`startUpTimestamp`) on Reconnect
In [RtmpEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/rtmp/src/main/java/io/github/thibaultbee/streampack/ext/rtmp/elements/endpoints/RtmpEndpoint.kt#L232-L238) and [L265-L267](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/rtmp/src/main/java/io/github/thibaultbee/streampack/ext/rtmp/elements/endpoints/RtmpEndpoint.kt#L265-L267):
- `RtmpEndpoint` computes FLV tag timestamps relative to `startUpTimestamp` (`ts = (frame.ptsInUs - startUpTimestamp) / 1000`), and `stopStream()` resets `startUpTimestamp = INVALID_TIMESTAMP` so the next `open()` + `startStream()` starts FLV timestamps back at `0`.
- Starting a newly established RTMP TCP connection (`connect` -> `createStream` -> `publish`) at timestamp `0` (or the first frame's PTS after reconnect) is standard across RTMP ingest servers, **provided** both audio and video sequence headers are re-sent on the new connection (`flvTagBuilder` reset) and `startUpTimestamp` is re-initialized from the first frame written to the new session.

---

## 4. Proposed Approach to Improve Reconnection

To keep modifications to your **StreamPack** fork minimal while properly supporting simultaneous recording + streaming and safe RTMP reconnection, we have two architectural paths to choose from for simultaneous recording, plus a set of targeted reconnection fixes.

### Decision 1: Choose Between `DualEndpoint` (Single Encoder) vs. `DualStreamer` / `StreamerPipeline` (Two `EncodingPipelineOutput`s)

| Aspect | Approach 1A: `DualEndpoint` (Single Encoder, 2 Endpoints) | Approach 1B: `StreamerPipeline` / `DualStreamer` (2 `EncodingPipelineOutput`s) |
| :--- | :--- | :--- |
| **Hardware Resource Usage** | **Lowest** — 1 Video Encoder + 1 Audio Encoder total. Encoded `Frame` buffers are duplicated to file muxer and network endpoint. | **Moderate** — 2 Video Encoders + 2 Audio Encoders (or 1 shared audio + 2 video). Most modern Android SoCs easily handle 2x 1080p hardware encoders, though 2x 4K60 can hit hardware codec limits on midrange devices. |
| **Effect of Adaptive Bitrate (ABR) on Recording** | **Shared** — When network degrades, the SRT/RTMP bitrate regulator lowers the single video encoder's bitrate, so the **local file recording also drops quality/bitrate**. | **Isolated** — Local recording uses a fixed high bitrate (and even different codec/container/resolution if desired), completely unaffected by network ABR. |
| **Pipeline Behaviour on Network Disconnect** | Sources and encoders **never stop** during network disconnect—even when *not* recording, if we use a resilient endpoint wrapper! | `StreamerPipeline` already keeps `AudioInput` and `VideoInput` running whenever `recordOutput.isStreaming == true`. Only `liveOutput` stops and restarts. |
| **Impact on `LifeStreamer` Codebase** | **Minimal** — Keeps `ISingleStreamer` / `SingleStreamer` everywhere in `CameraStreamerService` and `PreviewViewModel`. | **Medium** — `CameraStreamerService` and `PreviewViewModel` are currently typed around `ISingleStreamer` / `SingleStreamer`, so they would either need a wrapper or adaptation for a second output. |

---

### Recommended Path: Endpoint-Level Reconnection (Keep Pipeline & Encoders Running)

Notice that you don't actually need to stop `StreamerPipeline`, `AudioInput`, `VideoInput`, or `MediaCodecEncoder` **at all** during a network disconnect—whether local recording is enabled or not!

Why does `SingleStreamer` currently stop the encoders and sources on a network drop?
Only because of **two places** in `EncodingPipelineOutput.kt`:
1. Line 346: `endpointInternal.isOpenFlow.filter { !it }.drop(1).collect { stopStream() }`
2. Lines 318 & 337: `endpointInternal.write(frame, it)` throwing `ClosedException` into `onInternalError(t)`, which calls `stopStream()`.

When `DualEndpoint` (or a custom `CombineEndpoint` in LifeStreamer) is used:
- If at least one endpoint is open, `CombineEndpoint` **already suppresses** both of those!
- Even better: if we handle remote endpoint reconnection **at the endpoint level** (without letting `EncodingPipelineOutput` tear down the encoders and camera/mic sources):
  1. **Camera/UVC/RTMP/Screen sources and `MediaCodec` encoders keep running uninterrupted** during the 5-second reconnect cycle (no encoder surface recreation, no orientation unlock/relock glitches, no Bluetooth SCO re-negotiation, no wake-lock drops, no audio hiccups).
  2. **Local file recording (`mainEndpoint`) continues writing every single frame to disk seamlessly** while `secondEndpoint` (RTMP/SRT/SRTLA) is disconnected and reconnecting.
  3. **Even when local file recording is OFF**, keeping the pipeline and encoders running while only reconnecting the remote `IEndpoint` makes live stream reconnection much faster and cleaner!

---

### Concrete Step-by-Step Proposal

#### Step 1: Small, Targeted Fixes in StreamPack Fork (3 small changes)
1. **`FlvStream.kt` — Guard against sending P-frames before the video sequence header**:
   In [FlvStream.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/flv/src/main/java/io/github/thibaultbee/streampack/ext/flv/elements/endpoints/composites/muxer/utils/FlvStream.kt#L63-L71), in `VideoFlvStream.createImpl(frame)`:
   ```kotlin
   override fun createImpl(frame: Frame): List<FLVData> {
       var withSequenceStart = false
       if (!sentSequenceStart) {
           if (!frame.isKeyFrame) {
               return emptyList() // Wait for keyframe before sending sequence header & video frames
           }
           withSequenceStart = true
           sentSequenceStart = true
       }
       return frameFactory.create(frame, withSequenceStart)
   }
   ```
   And in [FlvTagBuilder.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/flv/src/main/java/io/github/thibaultbee/streampack/ext/flv/elements/endpoints/composites/muxer/utils/FlvTagBuilder.kt#L131-L138), if `flvDatas.isEmpty()`, immediately call `frame.close()`.
2. **`RtmpEndpoint.kt` — Only send `deleteStream()` on graceful stop, and ensure muxer state resets on disconnect/close**:
   - In [RtmpEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/rtmp/src/main/java/io/github/thibaultbee/streampack/ext/rtmp/elements/endpoints/RtmpEndpoint.kt#L206-L226), when `socketContext.invokeOnCompletion` fires (unexpected disconnect) or in `close()`, clear `flvTagChannel`, call `flvTagBuilder.clearStreams()`, and reset `startUpTimestamp = INVALID_TIMESTAMP`.
   - Avoid calling `rtmpClient?.deleteStream()` when tearing down after an error/disconnect (or add a flag/method to close without sending `deleteStream` during reconnection so we don't signal "End of Transmission" to the RTMP ingest server or waste 3 seconds waiting on a dead socket).
3. **`DualEndpoint.kt` / `CombineEndpoint.kt` — Properly re-register streams & gate writes on `secondEndpoint` restart**:
   - In `CombineEndpoint`, store the active `streamConfigs: List<CodecConfig>` passed to `addStreams()`.
   - Track whether each child endpoint is actively **started** (not just `isOpenFlow == true`), so `CombineEndpoint.write()` only writes frames to `secondEndpoint` *after* `startStreamSecond()` has registered streams and started the muxer/sink.
   - In `DualEndpoint.startStreamSecond()`, if `secondEndpoint` doesn't have entries in `endpointsToStreamIdsMap` (because `stopStreamSecond()` was called on disconnect), re-populate `endpointsToStreamIdsMap` via `secondEndpoint.addStreams(streamConfigs)` before calling `secondEndpoint.startStream()`.

#### Step 2: LifeStreamer Reconnection & RTMP Handling Improvements
1. **Isolate Remote Endpoint Reconnection from Pipeline Lifecycle**:
   - When the remote publishing endpoint (RTMP/SRT/SRTLA) disconnects while streaming:
     - Do **not** call `currentStreamer.stopStream()` if local recording is active (or in general, if we keep the pipeline running during the reconnect loop).
     - Instead, only stop/close the remote endpoint (`dualEndpoint.stopStreamSecond()` + `dualEndpoint.closeSecond()`), pause/detach the bitrate regulator during the disconnect gap, and after the 5-second timer:
       1. `dualEndpoint.openSecond(remoteDescriptor)`
       2. `dualEndpoint.startStreamSecond()`
       3. `videoEncoder?.requestKeyFrame()` (so the newly reconnected RTMP/SRT muxer immediately gets an IDR frame + SPS/PPS sequence header)
       4. Re-apply safe initial bitrate and re-attach the bitrate regulator.
2. **Do Not Release Wake Locks During Reconnection**:
   - In [CameraStreamerService.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt#L586-L595), if `_isReconnecting.value == true`, keep `wakeLock` and `networkWakeLock` held so Android does not throttle background network/timers during the 5-second reconnection delay.
3. **Skip Outbound Stream Restart on Inbound RTMP Source Disconnect When Publishing to RTMP (or Recording Locally)**:
   - In [PreviewViewModel.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L3082-L3089), when an inbound RTMP video source disconnects:
     - If the outbound publishing endpoint is **RTMP** (`sinkType == MediaSinkType.RTMP`) **or** if local file recording is active, always use the **hot-swap** fallback path (`handleRtmpDisconnectionWithHotSwap`) instead of `handleRtmpDisconnectionWithRestart`.
     - This prevents sending `deleteStream` to the outbound RTMP ingest server (which would terminate the live broadcast on YouTube/Twitch/etc.) and prevents interrupting the local file recording when an input camera/drone disconnects.

---

## Questions / Next Steps

1. **Simultaneous Recording Architecture**: Do you prefer staying with **`DualEndpoint` (single encoder)**—which you began prototyping on `v2/reconnection-dont-stop-start-pipeline` and uses the least CPU/battery, with the caveat that local recording shares the live stream's adaptive bitrate—or would you ever want **two independent `EncodingPipelineOutput`s (`DualStreamer`)** so local recording stays at a fixed high bitrate?
2. **Pipeline Running During Reconnect**: When local recording is **not** enabled and the live stream loses connection, would you also like to keep the camera/audio sources and encoders running while only the remote endpoint reconnects (or only keep the pipeline running when local recording is active)?