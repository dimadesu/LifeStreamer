/*
 * Copyright (C) 2022 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.utils

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Range
import androidx.annotation.RequiresPermission
import androidx.datastore.preferences.preferencesDataStore
import com.dimadesu.lifestreamer.ApplicationConstants.userPrefName
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.mediaStoreMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.backCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameraManager
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.frontCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isBackCamera
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import java.io.File

@RequiresPermission(Manifest.permission.CAMERA)
suspend fun IWithVideoSource.setNextCameraId(context: Context) {
    val cameras = context.cameraManager.cameras
    val videoSource = videoInput.sourceFlow.value

    val newCameraId = if (videoSource is ICameraSource) {
        val currentCameraIndex = cameras.indexOf(videoSource.cameraId)
        val newCameraIndex = (currentCameraIndex + 1) % cameras.size
        cameras[newCameraIndex]
    } else {
        context.defaultCameraId
    }

    setCameraId(newCameraId)
}

@RequiresPermission(Manifest.permission.CAMERA)
suspend fun IWithVideoSource.switchBackToFront(context: Context) {
    val cameraManager = context.cameraManager
    val videoSource = videoInput.sourceFlow.value
    val cameras = if (videoSource is ICameraSource) {
        if (cameraManager.isBackCamera(videoSource.cameraId)) {
            cameraManager.frontCameras
        } else {
            cameraManager.backCameras
        }
    } else {
        cameraManager.backCameras
    }

    if (cameras.isNotEmpty()) {
        setCameraId(cameras[0])
    } else {
        throw Exception("No camera available")
    }
}

/**
 * Gets the application's data store.
 */
val Context.dataStore by preferencesDataStore(
    name = userPrefName
)

private const val RECORDINGS_SUBFOLDER = "LifeStreamer"

fun Context.createVideoContentUri(name: String, mimeType: String? = null): UriMediaDescriptor {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val videoDetails = ContentValues().apply {
            put(MediaStore.Video.Media.TITLE, name)
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            if (mimeType != null) {
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$RECORDINGS_SUBFOLDER"
                )
            } else {
                @Suppress("DEPRECATION")
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    RECORDINGS_SUBFOLDER
                )
                directory.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, File(directory, name).absolutePath)
            }
        }
        return mediaStoreMediaDescriptor(this, videoDetails)
    } else {
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            RECORDINGS_SUBFOLDER
        )
        directory.mkdirs()
        val file = File(directory, name)
        return UriMediaDescriptor(Uri.fromFile(file))
    }
}

fun String.appendIfNotEndsWith(suffix: String): String {
    return if (this.endsWith(suffix)) {
        this
    } else {
        this + suffix
    }
}

val Range<*>.isEmpty: Boolean
    get() = upper == lower

fun Long.formatBitrate(): String {
    return when {
        this >= 1_000_000 -> String.format(java.util.Locale.US, "%.2f Mbps", this / 1_000_000.0)
        this >= 1_000 -> String.format(java.util.Locale.US, "%.2f kbps", this / 1_000.0)
        else -> "$this bps"
    }
}
