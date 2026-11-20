I have applied the planned changes across both **StreamPack** (`FlvStream.kt`, `FlvTagBuilder.kt`, `RtmpEndpoint.kt`, `DynamicEndpoint.kt`, `CombineEndpoint.kt`, and `DualEndpoint.kt`) and **LifeStreamer** (`CameraStreamerService.kt` and `PreviewViewModel.kt`).

I am now running the Gradle unit tests and debug build (`./gradlew testDebugUnitTest assembleDebug && ./gradlew -p StreamPack testDebugUnitTest`) and waiting for it to finish before committing the changes.

Ran verify test tasks and inspect git status in both repos
Ran review StreamPack diff before committing
Ran review LifeStreamer app diff before committing
Ran commit StreamPack and LifeStreamer changes and verify git status
Ran verify recent commits on reconnect-without-stopping branch

Sorry about the delay earlier — several stream interruptions occurred during plan hand-off before edits could start. Everything is now implemented, built, unit-tested, and committed in both **StreamPack** and **LifeStreamer**.

---

### Changes Implemented

#### 1. StreamPack Submodule (`StreamPack`, branch `update-to-3.2.1`)
1. **`b14183be5` — `fix(flv): drop leading video inter-frames until first keyframe and sequence header`**
   - [FlvStream.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/flv/src/main/java/io/github/thibaultbee/streampack/ext/flv/elements/endpoints/composites/muxer/utils/FlvStream.kt#L63-L74): In `VideoFlvStream.createImpl()`, drops non-keyframe video frames (`emptyList()`) while `!sentSequenceStart`, ensuring an AVC/HEVC sequence header (`SPS`/`PPS`) always precedes coded video frames after an in-flight reconnect.
   - [FlvTagBuilder.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/flv/src/main/java/io/github/thibaultbee/streampack/ext/flv/elements/endpoints/composites/muxer/utils/FlvTagBuilder.kt#L131-L134): Closes dropped `Frame` buffers immediately when `flvDatas.isEmpty()`.

2. **`07c0f9c6e` — `fix(rtmp): reset FLV muxer state on close/disconnect and avoid deleteStream after close`**
   - [RtmpEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/rtmp/src/main/java/io/github/thibaultbee/streampack/ext/rtmp/elements/endpoints/RtmpEndpoint.kt#L179-L250): Added `resetStreamState()` (clears `flvTagChannel`, `flvTagBuilder`, and resets `startUpTimestamp`) and guarded `socketContext.invokeOnCompletion` with `rtmpClient === closedClient` so stale socket completions cannot clobber a newly opened connection or emit spurious `ClosedException`s during an intentional `close()`.
   - [RtmpEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/rtmp/src/main/java/io/github/thibaultbee/streampack/ext/rtmp/elements/endpoints/RtmpEndpoint.kt#L319-L355): Updated `close()` to close the socket and reset FLV muxer state without calling `deleteStream()`.

3. **`c8df590ec` — `feat(core): support isolated DualEndpoint reconnection without stopping pipeline`**
   - [DynamicEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/DynamicEndpoint.kt#L95-L108) & [DynamicEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/DynamicEndpoint.kt#L167-L183): Wrapped `isOpenFlow.collect` in `launch { ... }` (fixing blocked `isOpenFlows` after endpoint switches) and stopped `TsMuxer` (`endpoint?.stopStream()`) when closing `srtEndpoint` directly via `close()`.
   - [CombineEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/CombineEndpoint.kt#L72-L296): Tracked `activeStreamConfigs` and `startedEndpoints`, gated `write()` on `it.isOpenFlow.value && startedEndpoints.contains(it)` (closing frames directly when no endpoint is started), forwarded unexpected child `isOpenFlow` closures via `_internalThrowableFlow`, intersected `info`/`getInfo` across active/supporting child endpoints, and added `isKeepAliveOnWriteError()`.
   - [DualEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/core/src/main/java/io/github/thibaultbee/streampack/core/elements/endpoints/DualEndpoint.kt#L42-L185): Implemented `WithEndpointMetrics<Any>`, added `_isSessionActiveFlow` to keep `isOpenFlow` and `isKeepAliveOnWriteError()` active across `secondEndpoint` disconnects, and re-registered streams in `startStream()` / `startStreamSecond()` after `closeSecond()` / `stopStreamSecond()`.

#### 2. LifeStreamer (`reconnect-without-stopping` branch)
4. **`5a802c7b` — `feat: reconnect live stream via DualEndpoint without stopping pipeline or sending RTMP deleteStream`**
   - [CameraStreamerService.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt#L71-L79): Configured `SingleStreamer` with `DualEndpointFactory(DynamicLocalEndpointFactory(), DynamicEndpointFactory())`; opened non-local descriptors via `dualEndpoint.openSecond(descriptor)` ([L1217-L1232](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt#L1217-L1232)); preserved `wakeLock`, `networkWakeLock`, and `streamingStartTime` across reconnection ([L598-L612](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt#L598-L612), [L1708-L1736](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt#L1708-L1736)).
   - [PreviewViewModel.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L825-L892): When the pipeline is already streaming (`isStreamingFlow.value == true`), `startServiceStreaming()` reconnects `secondEndpoint` in-flight (`openSecond` -> `startStreamSecond` -> `videoEncoder?.requestKeyFrame()` -> `applySafeInitialBitrate` -> `attachBitrateRegulator`) without stopping sources or encoders.
   - [PreviewViewModel.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L2449-L2507): `handleDisconnection()` detaches the bitrate regulator and calls `dualEndpoint.closeSecond()` instead of `currentStreamer.stopStream()` / `close()`, keeping the pipeline (and any future local file recording on `mainEndpoint`) running and avoiding RTMP `deleteStream()`.
   - [PreviewViewModel.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L2165-L2195): `stopStream()` cleanly tears down the running pipeline (`stopServiceStreaming()`, bitrate regulator removal, `MediaProjection` transfer, and `close()`) if the user manually stops while reconnecting.
   - [PreviewViewModel.kt](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L3166-L3375): Inbound RTMP source disconnects always use hot-swap when publishing to `MediaSinkType.RTMP`, and for SRT/SRTLA `handleRtmpDisconnectionWithRestart()` bounces only `dualEndpoint.closeSecond()` instead of stopping the whole pipeline.

---

### Verification
- Ran `./gradlew testDebugUnitTest assembleDebug && ./gradlew -p StreamPack testDebugUnitTest` after all final edits:
  - `:app:testDebugUnitTest` and `:app:assembleDebug` passed (`BUILD SUCCESSFUL`).
  - `:streampack-core:testDebugUnitTest` and `:streampack-ui:testDebugUnitTest` passed (`BUILD SUCCESSFUL`; `streampack-flv`, `streampack-rtmp`, `streampack-services`, and `streampack-srt` have no unit test sources).
  - Live network/device reconnection tests (`androidTest` / streaming against real RTMP/SRT/SRTLA ingest servers) were not run in this environment.

---
---
---

Here are a few ways to explain it depending on who you're talking to—from a quick analogy to a short release-note summary:

### 1. The Everyday Analogy (Best for non-technical people)
> **"Imagine a live TV broadcast where the camera crew is both recording to a hard drive and beaming the video out over a satellite truck.**
>
> **Before:** If the internet connection dropped for a moment, the app used to shut down the *entire studio*—turning off the camera, stopping the microphone, shutting down the video processor, and restarting everything from scratch 5 seconds later.
>
> **Now:** Only the internet connection itself reconnects in the background while the camera, microphone, and video processor keep running smoothly. That means reconnecting is faster and cleaner, it lays the groundwork for recording an uninterrupted backup video to your phone even when your cell signal drops, and it avoids accidentally telling platforms like YouTube or Twitch that your stream is 'finished' when you just hit a brief dead zone."

---

### 2. Three Simple Bullet Points (What actually gets better)
- **No more "full reboot" on a bad signal:** When your internet drops while streaming, the app no longer shuts down and restarts the camera, microphone, and video encoder—it keeps them running and only reconnects the network link.
- **Ready for uninterrupted local backup recording:** Because the camera and video encoder never stop during a network drop, when local file recording is enabled, the video saved to your phone won't get cut off or split up when your internet drops.
- **Safer reconnections on YouTube / Twitch (RTMP):** Previously, when reconnecting or switching a disconnected video input, the app could accidentally send a "stream ended" signal to the streaming server, which can cause platforms like YouTube or Twitch to end your broadcast completely instead of waiting for you to reconnect. Now it only sends that "stream ended" signal when you actually press **Stop**.

---

### 3. Short Release Note / Changelog Version
> **Smarter Stream Reconnection:** Improved how LifeStreamer recovers from connection drops on RTMP, SRT, and SRTLA. Instead of stopping and restarting the entire camera/audio pipeline when the network drops, the app now keeps the capture and encoding pipeline running while reconnecting in the background—preventing RTMP servers from prematurely ending your broadcast and preparing the app for uninterrupted local video recording.

---
---
---

Here is how to explain each of the three **StreamPack** commits in plain language:

---

### Commit 1: `fix(flv): drop leading video inter-frames until first keyframe and sequence header`
- **The Problem:** Video streams don't send a full picture on every single frame. Instead, they send one **complete picture** (a "keyframe") every couple of seconds, followed by dozens of tiny **"what moved since the last frame" updates** (plus an initial "instruction manual" that tells the video player the resolution and format). When we reconnected to a server while the video processor was already running, the app immediately started sending those partial "what moved" updates before sending the instruction manual and a complete starting picture—so the server couldn't make sense of the video.
- **What We Fixed:** When a new connection opens mid-stream, it now throws away those partial "what moved" frames for a split second until the very next **complete picture** arrives, then sends the instruction manual + complete picture first so the server can play the video cleanly right away.

---

### Commit 2: `fix(rtmp): reset FLV muxer state on close/disconnect and avoid deleteStream after close`
- **The Problem:** Two things could go wrong when an RTMP stream (used by YouTube, Twitch, etc.) lost connection:
  1. When cleaning up a broken connection, the app could try to send an official *"I'm done broadcasting (`deleteStream`)"* command—which tells YouTube or Twitch to permanently end the live stream instead of waiting for you to reconnect.
  2. When closing a broken connection without a full stop, the app forgot to reset its internal "new connection" checklist (like resetting the timer to 0 and remembering to resend the video/audio setup headers on the next connection).
- **What We Fixed:** Closing a dropped RTMP connection now hangs up the broken line quietly (without sending the *"broadcast is over"* signal to the server) and resets the internal checklist so the next connection starts fresh with a clean timer and fresh setup headers. It also ensures an old, slow-closing connection can't accidentally interfere with a brand-new connection that just opened.

---

### Commit 3: `feat(core): support isolated DualEndpoint reconnection without stopping pipeline`
- **The Problem:** StreamPack has a "splitter" (`DualEndpoint`) that lets one video processor send the same video to two places at once: **Output 1 (a file on your phone)** and **Output 2 (the live internet stream)**. However, it wasn't built to handle Output 2 disconnecting and reconnecting on the fly:
  - If you weren't recording a file and the internet dropped, the splitter reported that *everything* was closed, which caused the whole camera and video processor to shut down.
  - When Output 2 reconnected while the video processor was already running, the splitter didn't re-introduce the audio and video tracks to the new connection, and could try pushing video frames into the new connection before the server handshake was finished.
  - It also didn't pass along live network health stats (used for automatic video quality adjustment) or reliably report connection drops after switching between SRT and RTMP.
- **What We Fixed:** We upgraded the splitter so it keeps the "studio" (camera, mic, and video processor) alive even when the internet output disconnects, properly re-registers the audio/video tracks and waits for the handshake to finish before sending frames when the internet reconnects, and passes through live network stats so automatic bitrate adjustment works normally.