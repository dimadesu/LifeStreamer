package com.dimadesu.lifestreamer.ui.main.usecases

import android.content.Context
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer

class BuildStreamerUseCase(
    private val context: Context
) {
    /**
     * Build a new [SingleStreamer].
     *
     * Only create a new streamer if none exists yet.
     *
     * @param previousStreamer Previous streamer to reuse if available.
     */
    operator fun invoke(previousStreamer: SingleStreamer? = null): SingleStreamer {
        if (previousStreamer == null) {
            return SingleStreamer(context)
        }
        return previousStreamer
    }
}