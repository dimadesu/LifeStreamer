Ran check all ClosedException emissions in RtmpEndpoint

In short: **yes, leaving it as-is is generally fine**, because on real mid-stream network drops `closeStream` isn't actually sent anyway.

Here is why, based on [RtmpEndpoint.kt](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/rtmp/src/main/java/io/github/thibaultbee/streampack/ext/rtmp/elements/endpoints/RtmpEndpoint.kt#L218-L246):

### 1. Normal network disconnects (socket drop / server kick / timeout)
`socketContext.invokeOnCompletion` is the **only** place `RtmpEndpoint` triggers a disconnection (`_isOpenFlow.tryEmit(false)` / `ClosedException`). Inside that callback, it sets:

```kotlin
rtmpClient = null
isStarted = false
_isOpenFlow.tryEmit(false)
// ...then emits ClosedException
```

Because `rtmpClient` is already cleared to `null` **before** `PreviewViewModel` receives `ClosedException` and calls `dualEndpoint.closeSecond()` → `RtmpEndpoint.close()`, `clientToClose` is `null`—so **`closeStream` is not sent** on an unexpected network drop.

---

### 2. The rare case where `closeStream` *can* still be sent
`rtmpClient` is only non-null during `closeSecond()` if `openSecond()` succeeded (TCP + RTMP `connect` worked), the socket is still open, and something failed **right after**—specifically:
* `startStream()` / `startStreamSecond()` throws or hits its 10-second timeout ([RtmpEndpoint.kt#L301-L312](file:///Users/dmytroantonov/git/LifeStreamer/StreamPack/extensions/rtmp/src/main/java/io/github/thibaultbee/streampack/ext/rtmp/elements/endpoints/RtmpEndpoint.kt#L301-L312)), or
* encoders fail to become ready or the user hits Stop before `startStream()` runs ([PreviewViewModel.kt#L901-L942](file:///Users/dmytroantonov/git/LifeStreamer/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L901-L942)).

How that behaves on the server:
* **If `publish()` hadn't succeeded yet** (e.g., failed before publishing started, or user aborted): completely harmless—there is no live broadcast to end.
* **If `publish()` actually succeeded on the server right before a `10s` `startStream()` timeout** (or stalled waiting for `writeSetDataFrame()`): sending `closeStream` is the one subtle case where some RTMP ingest servers could treat `closeStream` as the publisher cleanly closing the stream rather than an unexpected drop (whereas others only end the broadcast on `FCUnpublish` / `deleteStream` or after a reconnect grace window).

So unless you test against your target ingest server (like YouTube/Twitch) and specifically observe broadcasts terminating after a stalled `startStreamSecond()` retry, you don't need to work around `komuxer`'s `closeStream`—the common mid-stream disconnect case already skips `deleteStream`, `FCUnpublish`, **and** `closeStream`.