# Camera Session Lifecycle

This document explains when and how the camera session is created in LifeStreamer.

## Overview

The camera session creation happens in multiple stages during the app lifecycle. Understanding this flow is important for debugging and optimizing camera behavior.

## Camera Session Creation Flow

### 1. Service Creation (onCreate)
**Location:** `CameraStreamerService.onCreate()`  
**When:** When the service starts (app launch or service restart)  
**What happens:**
- Service is promoted to foreground with CAMERA and MICROPHONE permissions
- Rotation provider is registered to track device orientation
- Camera session is NOT yet created at this stage

### 2. Camera Source Initialization (setVideoSource)
**Location:** `PreviewViewModel.initializeStreamerSources()` → `streamer.setVideoSource(CameraSourceFactory())`  
**When:** After service is ready and connected to the ViewModel  
**What happens:**
- CameraSourceFactory is registered with the streamer
- Camera characteristics are read (available cameras, capabilities)
- Physical camera session is NOT yet created - only the factory is registered
- No camera resources are held at this stage

### 3. Preview Start (startPreview)
**Location:** `PreviewFragment.startPreviewWhenReady()` → `preview.startPreview()`  
**When:** When the preview surface is ready and visible  
**What happens:**
- **This is where the actual camera session is created**
- Camera device is opened via Camera2 API
- Camera capture session is created with preview surface as output target
- Camera starts delivering frames to the preview surface
- User sees camera feed on screen

**Important:** This is the first point where physical camera hardware is accessed and a camera session exists.

### 4. Stream Start (startStream)
**Location:** `PreviewViewModel.startStream()` → `streamer.open()` → `streamer.startStream()`  
**When:** User presses the "Start Stream" button  
**What happens:**
- Encoder surfaces are created (video encoder)
- Camera capture session is reconfigured to add encoder surface as output target
- Camera now sends frames to both preview surface AND encoder surface
- Encoded frames are sent to the streaming endpoint (SRT/RTMP)

## Key Points

### When is the camera session actually created?
**Answer:** During `startPreview()` call in PreviewFragment (stage 3)

### Why is camera initialization split into stages?
- **Performance:** Delay heavy operations until needed
- **Permissions:** Wait for user to grant camera permissions
- **UI:** Allow UI to be ready before starting camera
- **Resource management:** Don't hold camera when not needed

### What if preview is not started before streaming?
- The stream start will fail with "camera not configured" error
- Preview MUST be started before streaming to ensure camera session exists

### Background streaming considerations
- Camera session must remain active during background streaming
- Preview is kept running even when app goes to background
- Stopping preview would close the camera session and stop the stream

## Logging Camera Session Creation

Key log messages to watch for camera session creation:

```
# Stage 1: Service created
CameraStreamerService: CameraStreamerService created and configured for background camera access

# Stage 2: Camera source registered
PreviewViewModel: Camera permission granted, setting video source
PreviewViewModel: Camera source already set

# Stage 3: Camera session created (ACTUAL CAMERA OPENING)
PreviewFragment: Preview started
io.github.thibaultbee.streampack: Camera opened successfully
io.github.thibaultbee.streampack: Camera capture session created

# Stage 4: Streaming with camera
PreviewViewModel: Stream started successfully
CameraStreamerService: Streaming started
```

## Troubleshooting

### Camera not available
- Check permissions (Camera, Microphone)
- Ensure no other app is using the camera
- Check logs for "Camera opened successfully" message

### Preview shows black screen
- Camera session may have failed to create
- Check logs for camera errors
- Preview surface may not be ready

### Stream starts but shows black video
- Camera session exists but encoder surface not added
- Check that preview was started before stream start
- Verify camera is not in use by another app

## Code References

- Service lifecycle: `app/src/main/java/com/dimadesu/lifestreamer/services/CameraStreamerService.kt`
- Camera initialization: `app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt`
- Preview/camera session: `app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewFragment.kt`
- Camera source factory: StreamPack library `io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory`
