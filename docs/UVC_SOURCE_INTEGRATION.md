# UVC Camera Source Integration for StreamPack

## Overview

`UvcVideoSource` is a custom video source for StreamPack that provides continuous video frames from USB cameras via the UVCAndroid library. It follows the same pattern as `RTMPVideoSource` with support for dual output (streaming + preview) and bitmap fallback capability.

## Architecture

### Key Components

1. **UvcVideoSource.kt** - Main source implementation

   - Extends `AbstractPreviewableSource`
   - Implements `IVideoSourceInternal`
   - Uses `ISurfaceProcessorInternal` for dual output
   - Manages UVC camera lifecycle through `CameraHelper`

2. **Surface Management**
   - Input surface from processor → UVC camera renders here
   - Output surface → Encoder receives frames
   - Preview surface → UI preview receives frames

### Design Pattern

```
UVC Camera (CameraHelper)
    ↓ (renders to)
Input Surface (from surface processor)
    ↓
Surface Processor
    ├→ Output Surface (encoder)
    └→ Preview Surface (UI)
```

## Integration Steps

### 1. Create CameraHelper Instance

The `CameraHelper` should be created and managed at the ViewModel or Activity level:

```kotlin
// In PreviewViewModel or similar
private var uvcCameraHelper: CameraHelper? = null

fun initializeUvcCamera(context: Context) {
    uvcCameraHelper = CameraHelper(context).apply {
        setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                Log.d(TAG, "UVC camera attached: ${device.deviceName}")
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                Log.d(TAG, "UVC camera opened")
            }

            override fun onCameraOpen(device: UsbDevice) {
                Log.d(TAG, "UVC camera ready")
                // Camera is ready - can now set as video source
            }

            override fun onCameraClose(device: UsbDevice) {
                Log.d(TAG, "UVC camera closed")
            }

            override fun onDeviceClose(device: UsbDevice) {
                Log.d(TAG, "UVC device closed")
            }

            override fun onDetach(device: UsbDevice) {
                Log.d(TAG, "UVC camera detached")
                // Switch to fallback source
            }
        })
    }
}
```

### 2. Switch to UVC Source

```kotlin
suspend fun switchToUvcSource(streamer: SingleStreamer, cameraHelper: CameraHelper) {
    try {
        // Optional: Add delay for clean source transition
        delay(300)

        // Set UVC video source
        streamer.setVideoSource(UvcVideoSource.Factory(cameraHelper))

        // Set audio source (microphone or MediaProjection)
        streamer.setAudioSource(MicrophoneSourceFactory())

        Log.i(TAG, "Switched to UVC camera source")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to set UVC source: ${e.message}", e)
    }
}
```

### 3. Handle Camera Lifecycle

```kotlin
// When camera disconnects, switch to fallback
override fun onDetach(device: UsbDevice) {
    CoroutineScope(Dispatchers.Main).launch {
        switchToBitmapFallback(streamer, fallbackBitmap)
    }
}

// When camera connects, switch back to UVC
override fun onCameraOpen(device: UsbDevice) {
    CoroutineScope(Dispatchers.Main).launch {
        switchToUvcSource(streamer, cameraHelper)
    }
}
```

### 4. Format Selection

Use the existing `VideoFormatDialogFragment` to change resolution/FPS:

```kotlin
fun showVideoFormatDialog() {
    val formatDialog = VideoFormatDialogFragment(
        cameraHelper.supportedFormatList,
        cameraHelper.previewSize
    )
    formatDialog.setOnVideoFormatSelectListener { size ->
        // Update camera format
        if (!cameraHelper.isRecording) {
            cameraHelper.stopPreview()
            cameraHelper.previewSize = size

            // Surface processor will handle the new size
            cameraHelper.startPreview()
        }
    }
    formatDialog.show(supportFragmentManager, "video_format")
}
```

## Features

### Dual Output Support

- **Streaming** - Continuous frames to encoder via surface processor
- **Preview** - Simultaneous preview in UI via separate surface
- **Automatic surface management** - Handles surface creation/recreation

### State Management

- Tracks streaming vs preview states independently
- Graceful cleanup with delayed stop (prevents flicker)
- Cancellable pending cleanup on restart

### Format Handling

- Caches current resolution from CameraHelper
- Updates info provider when format changes
- Default fallback to 1920x1080 if format unavailable

### Thread Safety

- All camera operations on main thread (Handler)
- Surface processor operations on appropriate dispatchers
- Synchronized state updates via StateFlow

## Fallback Strategy

Similar to RTMP source, UVC should have bitmap fallback:

```kotlin
suspend fun ensureVideoSource(
    streamer: SingleStreamer,
    cameraHelper: CameraHelper?,
    fallbackBitmap: Bitmap
) {
    if (cameraHelper != null && cameraHelper.isCameraConnected) {
        // Use UVC camera
        switchToUvcSource(streamer, cameraHelper)
    } else {
        // Fallback to bitmap
        switchToBitmapFallback(streamer, fallbackBitmap)
    }
}
```

## Cleanup

```kotlin
fun releaseUvcCamera() {
    uvcCameraHelper?.let { helper ->
        try {
            helper.stopPreview()
            helper.closeCamera()
            helper.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing UVC camera: ${e.message}")
        }
    }
    uvcCameraHelper = null
}
```

## Important Notes

1. **Shared CameraHelper** - Don't release CameraHelper in UvcVideoSource.release(), as it may be shared with UvcTestActivity

2. **Surface Operations** - Always use `addSurface(surface, isRecordable)` and `removeSurface(surface)`, not `startPreview(surface)`

3. **Format Changes** - Stop preview, update format, restart preview for resolution changes

4. **Thread Safety** - Camera operations must run on main thread via Handler

5. **Device Permissions** - Ensure CAMERA permission granted before using UVC camera

## Testing Checklist

- [ ] UVC camera preview shows in UI
- [ ] Streaming encodes UVC frames correctly
- [ ] Preview continues while streaming
- [ ] Format selection changes resolution
- [ ] Camera disconnect triggers fallback
- [ ] Camera reconnect switches back from fallback
- [ ] Multiple cameras can be selected via dialog
- [ ] No resource leaks on source switching
- [ ] Orientation changes don't crash
- [ ] Encoder receives continuous frames (no gaps)
