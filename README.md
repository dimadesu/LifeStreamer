# LifeStreamer app for Android

Live streaming app for Android based on [StreamPack SDK](https://github.com/ThibaultBee/StreamPack).

Can stream SRT with dynamic/adaptive bitrate alrogrithm as well as RTMP. A lot of features come from StreamPack by default, check the list [here](https://github.com/ThibaultBee/StreamPack?tab=readme-ov-file#features).

Can have bonding via [Bond Bunny app](https://github.com/dimadesu/bond-bunny).

Discord server: https://discord.gg/2UzEkU2AJW

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

I'll start with original camera demo from StreamPack. I'll tweak it and add new features on top of it.

### Top planned features

- [x] _(Can be polished more)_ Foreground service to allow streaming with app in background, phone locked and screen off.
- [x] Dynamic bitrate. I want to upgrade it to [Belabox](https://github.com/BELABOX/belacoder) or [Moblin](https://github.com/eerimoq/moblin) algorithm.
- RTMP as source.
  - I tried a few things. I looked into building RTMP server, like Moblin does. Maybe I'll try building it later.
  - I was able to get proof of concept working with [ExoPlayer](https://github.com/androidx/media).
  - I can run server like [MediaMTX](https://github.com/bluenviron/mediamtx) in [Termux](https://termux.dev/en/) on Android or somewhere on the network. [Watch this video to see how to do it.](https://youtu.be/5H0AZca3nk4?si=yaAxqQ5-FW5GnKpq&t=310)
  - I can stream RTMP from action camera to my server.
  - I give ExoPlayer RTMP URL to that server to play and it does all the hard work - handles RTMP connection, demuxes and decodes video/audio.
  - I sort of grab uncommpressed data from ExoPlayer and feed into StreamPack via custom video/audio source.
  - There are many things that can go wrong with RTMP source, but I got the basic version working.

### Planned minor features
- Re-connect on disconnect
- Render bitrate and stream status in UI

## Why StreamPack

Main features StreamPack provides out of the box that make sense to have as a base for this project:

- Ability to stream via SRT or RTMP.
- H.265 (aka HEVC) or H.264.
- Basic dynamic bitrate algorithm for SRT (it calls it "bitrate regulator").
- Foundation for implementing service to allow continue streaming in background with phone locked and screen off.
- It's designed to be extensible with custom video and audio sources.

### Demo apps

StreamPack includes 2 great demo apps that can use phone cameras or screen recorder for live streaming.

## Project status update 18 September 2025

Applies to [LifeStreamer v0.0.8](https://github.com/dimadesu/LifeStreamer/releases/tag/v0.0.8).

- :white_check_mark: Dynamic bitrate

  Seems to work well. 3 algorithms added. Can be configured in Settings. When "bitrate regulation" is enabled the only bitrate setting that is used is maximum bitrate under "bitrate regulation".

- :warning: Background mode aka foreground service

  Can be a bit unstable and stuttery.

  **Workaround:** either don't go into background at all OR stay in the foreground as much as possible OR lower maximum bitrate to ~2000 kbps.

  I have ideas how to approach fixing it.

- :warning:  RTMP as source

  Many things can glitch out. **Workaround** is to stream only RTMP source primarily in foreground - no switching between sources and apps.

  I tested streaming from DJI action cam 4 riding bicycle. I found configuration that works.

  At first, it was very glitchy, but then I got rid of anything that's not required. I kept only bare minimum: one SIM card, no Bond Bunny, etc.

  SRT to Belabox Cloud w/ "Belabox" bitrate regulator (all algorithms should work, I think). Max bitrate 5000kbps (can be set higher).

  Do only minimum steps required to start the stream:

  - Run MediaMTX server.
  - Start action cam stream to server.
  - Kill LifeStreamer app/service.
  - Start LifeStreamer app.
  - Switch to RTMP source.
  - Landscape orientation.
  - Start the stream.
  - Keep LifeStreamer app open, do not switch apps/sources. Do not go to settings screen. Do not lock the phone or turn the screen off.
  - This works well on Samsung S20 FE on a usable cell signal (assuming phone is not too hot, so no CPU throttling).
 
## Recommended solutions to most issues

**There are many bugs. General workaround for all of them: kill LifeStreamer app/service and start fresh. Sometimes something in settings glitches out - wipe app data or reinstall.**

Minimise transitions between foreground/background and stay in foreground for better performance with screen on and phone unlocked until background mode is fixed.

Once you find configuration that works get a solid sample size of that and then start carefully adding/changing things and test if it still works as expected.

Some settings can be changed on the fly. It's best to kill app/service and restart the app after changing settings - this seems to help.

Goal for near future is stabilising as much as possible and making sure it's solid. It will take some time to polish.

MVP sort of works. Use recommended workarounds if you encounter issues.
