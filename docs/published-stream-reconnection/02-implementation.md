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
