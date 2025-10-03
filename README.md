# LifeStreamer Android app

Live streaming app for Android designed for IRL streaming based on [StreamPack SDK](https://github.com/ThibaultBee/StreamPack).

## Features

- Publish streams over SRT with dynamic bitrate algorithm from [Belabox](https://belabox.net/) or [Moblin](https://github.com/eerimoq/moblin).
- RTMP as video/audio source - restream RTMP feed from action cameras as SRT HEVC with great dynamic bitrate.
- A lot of features come from StreamPack by default, check the list [here](https://github.com/ThibaultBee/StreamPack?tab=readme-ov-file#features).

![LifeStreamer screenshot](docs/LifeStreamer-screenshot.png)

Discord server: https://discord.gg/2UzEkU2AJW

## Apps that can work together

- [MediaSrvr](https://github.com/dimadesu/MediaSrvr) - Runs RTMP server on Android phone. You can publish RTMP stream to it from an action camera, for example.
- [LifeStreamer](https://github.com/dimadesu/LifeStreamer) - Can use RTMP as source: playback RTMP stream from server and restream it as SRT with great dynamic bitrate.
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
- Foreground service.
- Great dynamic bitrate algorithm.
- RTMP as video/audio source.

Stretch goal: USB/UVC as video/audio source.

## Roadmap

I've started with original camera demo from StreamPack. I'm tweaking it and adding new features on top of it.

### Main implemented features

- [x] Dynamic bitrate. I added [Belabox](https://github.com/BELABOX/belacoder) and [Moblin](https://github.com/eerimoq/moblin) algorithms. Can be changed via Settings.
- [x] Foreground service to allow streaming with app in background, phone locked and screen off.
  - Status: Usable, needs performance improvements
- [x] RTMP as source.
  - Status: Usable, still polishing.
  - Run RTMP server on your device using [MediaSrvr](https://github.com/dimadesu/MediaSrvr) app.
    - Alternatively, run [MediaMTX](https://github.com/bluenviron/mediamtx) in [Termux](https://termux.dev/en/). [Watch video to see how to set it up.](https://youtu.be/5H0AZca3nk4?si=yaAxqQ5-FW5GnKpq&t=310)
  - Stream RTMP from action camera to RTMP server.
  - Give LifeStreamer RTMP URL to that server to play and it will use it as video/audio source.
  - There are many things that can go wrong with RTMP source, but I got the basic version working.

### Planning to implement next

- Re-connect on disconnect
- Polish existing functionality and user flows

## Why StreamPack

Main features StreamPack provides out of the box that make sense to have as a base for this project:

- Ability to stream via SRT or RTMP.
- H.265 (aka HEVC) or H.264.
- Basic dynamic bitrate algorithm for SRT (it calls it "bitrate regulator").
- Foundation for implementing service to allow continue streaming in background with phone locked and screen off.
- It's designed to be extensible with custom video and audio sources.

### Demo apps

StreamPack includes 2 great demo apps that can use phone cameras or screen recorder for live streaming.

## Project status update 30 September 2025

Applies to [LifeStreamer v0.2.0](https://github.com/dimadesu/LifeStreamer/releases/tag/v0.2.0).

- :white_check_mark: Dynamic bitrate

  - 3 algorithms added: Belabox, Moblin fast, Moblin slow. All seems to work well. Still testing.
  - Algortihm can be configured in Settings.
  - When "bitrate regulation" is enabled the only bitrate setting that is used is maximum bitrate under "bitrate regulation".

- :warning: Background mode aka foreground service

  Can work under right conditions:

  - Don't use phone or other apps as much as possible during the streaming in background.
  - Don't set bitrate too hight. On my Samsung S20 FE it works w/o issues with 6000 kbps for full HD.
  
  Can be a bit unstable and stuttery otherwise. **Workarounds:**

  - Stay in the foreground as much as possible.
  - Don't go into background at all.
  - Lower maximum bitrate to ~2000 kbps (experiment what works for you) if you want to use other apps during the stream.

- :warning:  RTMP as source

  Can work under the right conditions. I recommened monitoring the stream on the 2nd phone. Do not use streaming phone for anything else as much as possible.

  - Start RTMP server.
  - Start action cam stream to server.
  - Kill LifeStreamer app/service.
  - Start LifeStreamer app.
  - Start the stream from LifeStreamer.
  - If you use Bond Bunny, you can periodically open Bond Bunny when internet starts getting worse - I think Android is trying to slow it down, when it's in background for too long. I need to look into it.
  - Works on my Samsung S20 FE on a usable cell signal (assuming phone is not too hot, so no CPU throttling).
 
## Recommended solutions to most issues

**There are bugs. General workaround for all of them: kill LifeStreamer app/service and start fresh. Sometimes something in settings glitches out - wipe app data or reinstall.**

Minimise transitions between foreground/background and stay in foreground for better performance with screen on and phone unlocked until background mode is fixed.

Once you find configuration that works get a solid sample size of that and then start carefully adding/changing things and test if it still works as expected.

Some settings can be changed on the fly. It's best to kill app/service and restart the app after changing settings - this seems to help.

Goal for near future is stabilising as much as possible and making sure it's solid. It will take some time to polish.

MVP sort of works. Use recommended workarounds if you encounter issues.
