# LifeStreamer - Android app for IRL live streaming

LifeStreamer is an Android app designed for IRL live streaming based on [StreamPack SDK](https://github.com/ThibaultBee/StreamPack).

[<img src="docs/google-play-store.svg">](https://play.google.com/store/apps/details?id=com.dimadesu.lifestreamer)

## Features

- Restream RTMP feed or USB video/audio (UVC) from action camera like DJI Osmo Action 4 as SRT HEVC/H.265 with amazing dynamic/adaptive bitrate algorithm from [Belabox](https://belabox.net/) or [Moblin](https://github.com/eerimoq/moblin).
- Can use SRTLA bonding via [Bond Bunny](https://github.com/dimadesu/bond-bunny) app.
- Can use feed from any RTMP or SRT server as source. For Android I built [MediaSrvr](https://github.com/dimadesu/MediaSrvr) app that can run RTMP server on Android devices.
- USB as source. Works with DJI Osmo Action 4 in 'Webcam' mode when connected to phone with one USB-C to USB-C cable. Also can work with Elgato Cam Link even when connected via USB hub. Feel free to test other UVC devices, like capture cards. I will mostly target DJI OA4 and Cam Link for now. Note: Phones can lower USB audio quality when USB video is used.
- Background mode (foreground service) allows streaming with app in background, phone locked and screen off. Phone limits access to resources in this mode, so performance can be worse. Test first and consider lowering video encoder settings and bitrate. Note: Performance has improved significantly since switching from "debug" to "release" builds.
- Aggressive infinite reconnect when app loses connection.
- Audio monitoring for all audio sources.
- Switch between all video and audio sources while streaming.
- A lot of features come from StreamPack by default, check the list [here](https://github.com/ThibaultBee/StreamPack?tab=readme-ov-file#features).

![LifeStreamer screenshot](docs/LifeStreamer-screenshot.png)

Share ideas or report issues in Discord https://discord.gg/2UzEkU2AJW or create Git issues.

## Sources

### Video

- Phone cameras.
- RTMP video. [Watch RTMP source demo.](https://www.youtube.com/watch?v=_zlWsQYxrE4)
- SRT video.
- USB Video (UVC). [Watch USB source demo.](https://www.youtube.com/watch?v=RlPWbekqPx4)

### Audio

- Built-in phone microphones.
- USB audio: USB headphones, USB audio interfaces, wireless mic receivers, etc.
  - With USB video LifeStreamer is using USB audio from USB camera if available.
- Mics from Bluetooth headphones, earbuds, etc.
- For RTMP and SRT sources app uses Media Projection Audio to record RTMP/SRT player audio - kind of like phone screen recorder.

## Apps that can work together

See the [demo video on YouTube](https://www.youtube.com/watch?v=_zlWsQYxrE4).

- [MediaSrvr](https://github.com/dimadesu/MediaSrvr) - Runs RTMP server on Android phone. You can publish RTMP stream to it from an action camera, for example.
- LifeStreamer - Can use RTMP as source: playback RTMP stream from server and restream it as SRT with great dynamic bitrate.
- [Bond Bunny](https://github.com/dimadesu/bond-bunny) - You can use LifeStreamer to publish SRT stream into Bond Bunny app. Bond Bunny accepts SRT as input and forwards packets to SRTLA server like Belabox Cloud. Uses multiple networks to improve stream quality.

## How to install

### Google Play Store

You can install app from Google Play Store. Follow [this link](https://play.google.com/store/apps/details?id=com.dimadesu.lifestreamer).

#### Become alpha tester (aka "closed testing")

Please join alpha testing to test early versions of the app. More details [here](https://gist.github.com/dimadesu/00283dc48a672d6d9468126adeaf8566).

Note: Now that app is published publicly on the Play Store there is also "open testing" (beta?) - I'm still figuring out how it works.

### GitHub releases

I was originally releasing .apk files using [GitHub releases](https://github.com/dimadesu/LifeStreamer/releases). I plan to continue releasing on GitHub as a backup.

Open [GitHub releases page](https://github.com/dimadesu/LifeStreamer/releases) on your phone, download .apk file and install.

#### ⚠️ Note on "debug" VS. "release" builds

I am in the process of switching GitHub releases to "release" builds from "debug" builds. They have much better performance. This actually fixed "background mode" performance issues.

Starting from version [1.20.0](https://github.com/dimadesu/LifeStreamer/releases/tag/v1.20.0) I plan to publish "release" .apk builds via GitHub releases.

If you already have older .apk version from GitHub installed, you need to uninstall previous "debug" version first before installing "release" .apk as they are incompatible. Settings from "debug" build cannot be transferred to "release" build.

I used to publish only "debug" builds on GitHub. It was possible to install new version as an update on top of the old one without losing settings.

Once you switch over to "release" builds, it will be possible to update without losing settings (as long as you keep using "release" builds).

## My goals

My original motiviation for this project was to improve live streaming for action cameras like DJI Osmo Action 4 or GoPro.
As of now (September 2025) they can only stream RTMP which usually diconnects a lot on unstable internet.
I want to restream RTMP as SRT HEVC with great dynamic bitrate algorithm. That should fix it.

## Why StreamPack

Main features StreamPack provides out of the box that make sense to have as a base for this project:

- Ability to stream via SRT or RTMP.
- H.265 (aka HEVC) or H.264.
- Basic dynamic bitrate algorithm for SRT (it calls it "bitrate regulator").
- Foundation for implementing service to allow continue streaming in background with phone locked and screen off.
- It's designed to be extendable with custom video and audio sources.

### Demo apps

StreamPack includes 2 great demo apps that can use phone cameras or screen recorder for live streaming.

## Recommended solutions to most issues

**General workaround for issues: kill LifeStreamer app/service and start fresh. Sometimes something in settings glitches out - wipe app data or reinstall.**

**Settings can be changed during the stream, but won't apply until you restart the stream.**

**Note: Max/target bitrate under bitrate regulation in settings can be changed on the fly during the stream - no need to restart the stream to apply.**

Use recommended workarounds if you encounter issues.

## Apps that can stream SRT on Android

- IRL Pro (free)
- Larix Broadcaster (subscription)
- Larix Screencaster (subscription)
- Can do HDMI/USB/UVC as input:
  - USB Camera (free with ads) / USB Camera Pro (one-time payment to remove ads)
  - CameraFi (free version with ads or subscription)

## Similar/related projects

- [IRL Pro](https://irlpro.app/)
- [BELABOX](https://belabox.net/)
- [Moblin](https://github.com/eerimoq/moblin)
- [Moblink](https://github.com/eerimoq/moblink)

## FAQ

### Chat?

There are existing chat apps for Android like [Stream Buddy](https://play.google.com/store/apps/details?id=com.streamomation.streamerchat). I suggest you do side-by-side view with LifeStramer and chat app if your phone has this feature or use 2nd phone for chat.

### Overlays?

I highly recommend adding overlays in OBS that restreams SRT.

### Can LifeStreamer be combined with Bond Bunny and MediaSrvr?

In theory yes, but there are many benefits to having them separate, so no plans to combine. In general, I'm not a big fan of idea of having everything in one app. I'd rather have different focused apps each doing particular thing really well.
