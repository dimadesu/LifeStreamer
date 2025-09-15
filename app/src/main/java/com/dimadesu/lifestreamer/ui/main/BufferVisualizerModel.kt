package com.dimadesu.lifestreamer.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.dimadesu.lifestreamer.rtmp.audio.buffer.CircularPcmBuffer

/**
 * A centralized model to handle the lifecycle of resources like buffers for the Buffer Visualizer.
 */
object BufferVisualizerModel {
    private const val TAG = "BufferVisualizerModel"

    var circularPcmBuffer: CircularPcmBuffer? = null

    private val _isStreaming = MutableLiveData(false)
    val isStreaming: LiveData<Boolean> get() = _isStreaming

    fun setStreamingState(isStreaming: Boolean) {
        _isStreaming.postValue(isStreaming)
    }
}