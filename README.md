# LifeStreamer Android app

Live streaming app for Android designed for IRL streaming based on [StreamPack SDK](https://github.com/ThibaultBee/StreamPack).

## Features

- Restream RTMP feed or USB video/audio (UVC) from action camera like DJI Osmo Action 4 as SRT HEVC/H.265 with amazing dynamic/adaptive bitrate algorithm from [Belabox](https://belabox.net/) or [Moblin](https://github.com/eerimoq/moblin).
- Can use SRTLA bonding via [Bond Bunny](https://github.com/dimadesu/bond-bunny) app.
- Can use any RTMP server as source. For Android I built [MediaSrvr](https://github.com/dimadesu/MediaSrvr) app that can run RTMP server on Android devices.
- USB as source. Works with DJI Osmo Action 4 in 'Webcam' mode when connected to phone with one USB-C to USB-C cable. Also can work with Elgato Cam Link even when connected via USB hub. Feel free to test other UVC devices, like capture cards. I will mostly target DJI OA4 and Cam Link for now. Note: phones can lower USB audio quality when USB video is used.
- Background mode (foreground service) allows streaming with app in background, phone locked and screen off. (Phone limits access to resources in this mode, so performance is worse. Test first and consider lowering video encoder settings and bitrate.)
- Aggressive infinite reconnect when app loses connection.
- Audio monitoring for all audio sources.
- Switch between all video and audio sources while streaming.
- A lot of features come from StreamPack by default, check the list [here](https://github.com/ThibaultBee/StreamPack?tab=readme-ov-file#features).

![LifeStreamer screenshot](docs/LifeStreamer-screenshot.png)

Share ideas or report issues in Discord https://discord.gg/2UzEkU2AJW or create Git issues.

## Sources

### Video

- Phone cameras.
- RTMP video.
- USB Video (UVC).

### Audio

- Built-in phone microphones.
- USB audio: USB headphones, USB audio interfaces, wireless mic receivers, etc.
  - With USB video LifeStreamer is using USB audio from USB camera if available.
- Mics from Bluetooth headphones, earbuds, etc.
- For RTMP source app uses Media Projection Audio to record RTMP player audio - kind of like phone screen recorder.

## Apps that can work together

See the [demo video on YouTube](https://www.youtube.com/watch?v=_zlWsQYxrE4).

- [MediaSrvr](https://github.com/dimadesu/MediaSrvr) - Runs RTMP server on Android phone. You can publish RTMP stream to it from an action camera, for example.
- LifeStreamer - Can use RTMP as source: playback RTMP stream from server and restream it as SRT with great dynamic bitrate.
- [Bond Bunny](https://github.com/dimadesu/bond-bunny) - You can use LifeStreamer to publish SRT stream into Bond Bunny app. Bond Bunny accepts SRT as input and forwards packets to SRTLA server like Belabox Cloud. Uses multiple networks to improve stream quality.

## How to install

For now, I'll be releasing APK files using [GitHub releases](https://github.com/dimadesu/LifeStreamer/releases).

Open [GitHub releases page](https://github.com/dimadesu/LifeStreamer/releases) on your phone, download APK and install.

## My goals

My original motiviation for this project was to improve live streaming for action cameras like DJI Osmo Action 4 or GoPro.
As of now (September 2025) they can only stream RTMP which usually diconnects a lot on unstable internet.
I want to restream RTMP as SRT HEVC with great dynamic bitrate algorithm. That should fix it.

I am building an app for myself that other people can use too. I'll be focusing on main core functionality, not gimmicky features.

Top priorities:
- Great dynamic aka adaptive bitrate algorithm.
- Foreground service / background mode.
- RTMP as video/audio source.

Stretch goal: USB/UVC as video/audio source.

## Roadmap

I've started with original camera demo from StreamPack. I'm tweaking it and adding new features on top of it.

### Details about main implemented features

- [x] Dynamic/adaptive bitrate. I added [Belabox](https://github.com/BELABOX/belacoder) and [Moblin](https://github.com/eerimoq/moblin) algorithms. Can be changed via Settings.
- [x] Background mode (foreground service) to allow streaming with app in background, phone locked and screen off.
  - Status: usable, needs performance improvements.
- [x] RTMP as source.
  - Status: stable.
  - Run RTMP server on your device using [MediaSrvr](https://github.com/dimadesu/MediaSrvr) app.
    - Alternatively, run [MediaMTX](https://github.com/bluenviron/mediamtx) in [Termux](https://termux.dev/en/). [Watch video to see how to set it up.](https://youtu.be/5H0AZca3nk4?si=yaAxqQ5-FW5GnKpq&t=310)
  - Stream RTMP from action camera to RTMP server.
  - Give LifeStreamer RTMP URL to that server to play and it will use it as video/audio source.
  - There are many things that can go wrong with RTMP source. I think it's working pretty good now.
- [x] Reconnect on disconnect.
- [x] USB video/audio as source.
- [x] Audio monitoring for all sources.

### Planning to implement next

- Polish existing functionality and user flows.

## Why StreamPack

Main features StreamPack provides out of the box that make sense to have as a base for this project:

- Ability to stream via SRT or RTMP.
- H.265 (aka HEVC) or H.264.
- Basic dynamic bitrate algorithm for SRT (it calls it "bitrate regulator").
- Foundation for implementing service to allow continue streaming in background with phone locked and screen off.
- It's designed to be extendable with custom video and audio sources.

### Demo apps

StreamPack includes 2 great demo apps that can use phone cameras or screen recorder for live streaming.

## Project status update 19 October 2025

Applies to [LifeStreamer v0.4.1](https://github.com/dimadesu/LifeStreamer/releases/tag/v0.4.1).

- :white_check_mark: Dynamic bitrate

  - 3 algorithms added: Belabox, Moblin fast, Moblin slow. All seems to work well. Still testing.
  - Algortihm can be configured in Settings.
  - When "bitrate regulation" is enabled the only bitrate setting that is used is maximum bitrate under "bitrate regulation".

- :white_check_mark: RTMP as source

  It was stabilised a lot in the recent versions:

    - You can switch inputs to/from RTMP source with confidence - doesn't glitch out.
    - UI was updated to explain what it is going on with RTMP stream.
    - If you stop/start RTMP source stream LifeStreamer should handle it w/o issues.
    - Retries for playing RTMP stream.
    - Etc.
  
  Be careful with background mode when using RTMP as source. RTMP source by itself should be fine, but background mode has performance limitations. I recommened monitoring the stream on the 2nd phone. Do not use streaming phone for anything else as much as possible, keep LifeStreamer app open (in foreground) for best performance.

  What works:

  - Start RTMP server.
  - Start action cam stream to RTMP server.
  - Kill LifeStreamer app/service. _(Optional, just to get super clean app state before starting.)_
  - Start LifeStreamer app.
  - Switch to RTMP source.
  - Start the stream from LifeStreamer.
  - Notes:
    - If you use Bond Bunny make sure to use version like [v1.0.5](https://github.com/dimadesu/bond-bunny/releases/tag/v1.0.5) it had some important updates to work better.
    - Works on my Samsung S20 FE on a usable cell signal (assuming phone is not too hot, so no CPU throttling).

- :warning: Background mode

  It already works well if you don't get greedy and push video settings too far.

  Phone has a lot less CPU and other resources available to apps in the background.
  Either lower your video endcoder settings if you want to use other apps on your phone while streaming OR avoid using other apps to not compete for resources.

  Looks like there is no magic fix - performance optimisations required.

  Example. My phone is Samsung S20 FE. I stream full HD 30fps.

  - If I don't use phone/apps: it works w/o issues at 6000 kbps - I wouldn't set it higher.
  - If I want to use maps, browser, etc. during the stream: 2500 kbps max bitrate seems to work without major glitches.

  **Note: Max/target bitrate under bitrate regulation in settings can be changed on the fly during the stream - no need to restart the stream to apply.**
  
  Otherwise, stream can have visual glitches and stuttery audio. **Workarounds:**

  - Stay in the foreground as much as possible.
  - Don't go into background at all.

- :white_check_mark:  Reconnect on disconnect

  Should be pretty stable. Still testing.

## Recommended solutions to most issues

**There are bugs. General workaround for all of them: kill LifeStreamer app/service and start fresh. Sometimes something in settings glitches out - wipe app data or reinstall.**

Minimise transitions between foreground/background and stay in foreground for better performance with screen on and phone unlocked until background mode performance is optimised OR embrace background mode limitations.

**Settings can be changed during the stream, but won't apply until you restart the stream.**

Goal for near future is stabilising as much as possible and making sure it's solid. It will take some time to refine.

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
