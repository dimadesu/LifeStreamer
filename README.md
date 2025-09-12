# LifeStreamer app for Android

Live streaming app for Android based on [StreamPack SDK](https://github.com/ThibaultBee/StreamPack).

Can stream SRT with dynamic/adaptive bitrate alrogrithm as well as RTMP. A lot of features come from StreamPack by default, check the list [here](https://github.com/ThibaultBee/StreamPack?tab=readme-ov-file#features).

Can have bonding via [Bond Bunny app](https://github.com/dimadesu/bond-bunny).

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

- Foreground service to allow streaming with app in background, phone locked and screen off.
- Dynamic bitrate. I want to upgrade it to [Belabox](https://github.com/BELABOX/belacoder) or [Moblin](https://github.com/eerimoq/moblin) algorithm.
- RTMP as source.
  - I tried a few things. I looked into building RTMP server, like Moblin does. Maybe I'll try building it later.
  - I was able to get proof of concept working with [ExoPlayer](https://github.com/androidx/media).
  - I can run server like [MediaMTX](https://github.com/bluenviron/mediamtx) in [Termux](https://termux.dev/en/) on Android or somewhere on the network. [Watch this video to see how to do it.](https://youtu.be/5H0AZca3nk4?si=yaAxqQ5-FW5GnKpq&t=310)
  - I can stream RTMP from action camera to my server.
  - I give ExoPlayer RTMP URL to that server to play and it does all the hard work - handles RTMP connection, demuxes and decodes video/audio.
  - I sort of grab uncommpressed data from ExoPlayer and feed into StreamPack via custom video/audio source.
  - There are many things that can go wrong with RTMP source, but I got the basic version be working.

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
