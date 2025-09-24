package com.dimadesu.lifestreamer.ui.views

import android.util.Size
import android.view.View

/**
 * Gets view size
 */
internal val View.size: Size
    get() = Size(width, height)
