package com.dimadesu.lifestreamer.models

import android.view.Surface

enum class StreamOrientation(val id: String) {
    AUTO("auto"),
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    PORTRAIT_REVERSED("portrait_reversed"),
    LANDSCAPE_REVERSED("landscape_reversed");

    fun toSurfaceRotation(): Int? = when (this) {
        AUTO -> null
        PORTRAIT -> Surface.ROTATION_0
        LANDSCAPE -> Surface.ROTATION_90
        PORTRAIT_REVERSED -> Surface.ROTATION_180
        LANDSCAPE_REVERSED -> Surface.ROTATION_270
    }

    companion object {
        fun fromId(id: String): StreamOrientation =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}
