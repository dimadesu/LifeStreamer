package com.dimadesu.lifestreamer.rtmp.audio.buffer

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * A fixed-size, thread-safe circular buffer for audio frames.
 *
 * This buffer is designed to be filled with `ByteBuffer`s containing audio data
 * from a source (e.g., a microphone) and then drained by a consumer (e.g., a MediaCodec encoder).
 * It manages the total byte capacity and provides thread-safe access.
 *
 * @param byteCapacity The maximum total number of bytes the buffer can hold.
 */
class CircularPcmBuffer(private val byteCapacity: Int) {

    // Audio format tracking - updated when format is determined
    private var sampleRateInternal: Int = 48000
    private var channelCountInternal: Int = 2
    private var bytesPerSampleInternal: Int = 2

    // Adaptive buffering parameters
    private var initialFillThreshold = 20 // Start with 20% fill
    private var minBufferThreshold = 5    // Minimum 5% before switching to fill mode
    private var underrunCount = 0         // Track buffer underruns
    private var lastReadTime = 0L         // Track read timing

    // A data class to hold the audio frame's ByteBuffer and its presentation timestamp.
    private data class AudioFrame(val data: ByteBuffer, val timestamp: Long)

    // A thread-safe queue to manage the individual AudioFrame objects.
    // The capacity is a number of frames, not bytes.
    // We choose a generous capacity to prevent blocking on the queue itself,
    // as the main bottleneck will be the total byte capacity.
    private val frameBuffer = ArrayBlockingQueue<AudioFrame>(5000)

    // An atomic integer to track the total number of bytes currently in the buffer.
    private val availableBytes = AtomicInteger(0)

    // A state variable to manage the "filling" vs. "draining" state of the buffer.
    private var isFilling = true

    // If a consumer only reads part of a queued chunk, keep the remainder here for next read.
    private var pendingFrame: AudioFrame? = null

    /**
     * Gets the number of bytes currently available in the buffer.
     */
    val available: Int
        get() = availableBytes.get()

    /**
     * Gets the total byte capacity of the buffer.
     */
    val capacity: Int
        get() = byteCapacity

    /**
     * Updates the audio format parameters. Called when the actual format is determined
     * from ExoPlayer's audio sink configuration.
     */
    fun updateFormat(sampleRate: Int, channelCount: Int, bytesPerSample: Int) {
        val oldSampleRate = this.sampleRateInternal
        val oldChannelCount = this.channelCountInternal
        val oldBytesPerSample = this.bytesPerSampleInternal

        this.sampleRateInternal = sampleRate
        this.channelCountInternal = channelCount
        this.bytesPerSampleInternal = bytesPerSample

        if (oldSampleRate != sampleRate || oldChannelCount != channelCount || oldBytesPerSample != bytesPerSample) {
            Log.i("CircularPcmBuffer", "Format changed: $oldSampleRate→$sampleRate Hz, $oldChannelCount→$channelCount ch, $oldBytesPerSample→$bytesPerSample bytes/sample")

            // Warn about significant sample rate mismatches that could cause audio quality issues
            if (oldSampleRate != sampleRate && abs(oldSampleRate - sampleRate) > 1000) {
                Log.w("CircularPcmBuffer", "WARNING: Large sample rate change detected! This could cause audio pitch/speed issues. Old: $oldSampleRate Hz, New: $sampleRate Hz")
            }
        } else {
            Log.d("CircularPcmBuffer", "Format update called but no change: sampleRate=$sampleRate, channelCount=$channelCount, bytesPerSample=$bytesPerSample")
        }
    }

    /**
     * Clears the buffer, removing all frames and resetting the available byte count.
     */
    fun clear() {
        Log.i("CircularPcmBuffer", "Clearing buffer. Available bytes: ${availableBytes.get()}")
        frameBuffer.clear()
        availableBytes.set(0)
        isFilling = true
        // Reset adaptive buffering parameters
        underrunCount = 0
        initialFillThreshold = 20
        lastReadTime = 0L
        Log.i("CircularPcmBuffer", "Buffer cleared and reset to filling mode.")
    }

    /**
     * Writes an audio frame with its timestamp to the buffer.
     * This method is thread-safe and optimized for streaming performance.
     *
     * @param data The ByteBuffer containing the audio data. The ByteBuffer's position and limit
     * will be used to determine the size of the data to be written.
     * @param timestamp The presentation timestamp for this frame in microseconds.
     * @return `true` if the frame was successfully written, `false` if the buffer is full.
     */
    fun writeFrame(data: ByteBuffer, timestamp: Long) {
        val totalSize = data.remaining()
        Log.v("CircularPcmBuffer", "Writing frame. Size: $totalSize, Timestamp: $timestamp")

        val frame = AudioFrame(data, timestamp)
        val success = frameBuffer.offer(frame)

        if (success) {
            availableBytes.addAndGet(data.limit())
            Log.v("CircularPcmBuffer", "Frame written. Available: ${availableBytes.get()}/${byteCapacity}")
        } else {
            Log.w("CircularPcmBuffer", "Buffer full! Dropping audio frame. Size: $totalSize")
        }
    }

    /**
     * Reads an audio frame with its timestamp from the buffer.
     * This method is thread-safe and implements a refined buffering strategy:
     * - Uses a lower initial fill threshold (20%) to reduce latency
     * - Maintains minimum buffer level (10%) to prevent underruns
     * - Provides smoother streaming for real-time applications
     *
     * @return A `Pair` of `ByteBuffer` and timestamp if a frame is available, or `null` otherwise.
     */
    fun readFrame(size: Int): Pair<ByteBuffer, Long>? {
        // The `isFilling` state prevents premature reading.
        if (isFilling) {
            // Check if the buffer has reached the adaptive fill level to start draining.
            if (isAtLeastFull(initialFillThreshold)) {
                isFilling = false
                Log.i("CircularPcmBuffer", "Switching to draining mode. Available: ${availableBytes.get()}, Threshold: $initialFillThreshold%")
            } else {
                // Less verbose logging to reduce performance impact
                Log.v("CircularPcmBuffer", "Still filling. Available: ${availableBytes.get()}/${byteCapacity}")
                return null // Still filling, so no frame is available yet.
            }
        }

        val currentFrame = pendingFrame ?: frameBuffer.poll()
        if (currentFrame == null) {
            // Keep some minimum buffer to avoid frequent fill/drain switches
            if (isAtLeastFull(minBufferThreshold)) {
                // Try to wait for more data rather than immediate fill mode switch
                Log.v("CircularPcmBuffer", "Buffer low but not empty, waiting for more data")
                return null
            }
            // Buffer underrun detected - adapt buffering strategy
            underrunCount++
            if (underrunCount > 5 && initialFillThreshold < 40) {
                initialFillThreshold += 5 // Increase buffer threshold
                Log.w("CircularPcmBuffer", "Frequent underruns detected. Increasing fill threshold to $initialFillThreshold%")
            }

            // If buffer is truly empty, switch back to filling state
            isFilling = true
            Log.i("CircularPcmBuffer", "Buffer underrun #$underrunCount. Switching to filling mode.")
            return null
        }

        pendingFrame = currentFrame

        val availableInChunk = currentFrame.data.remaining()
        val toRead = minOf(availableInChunk, size)

        Log.v("CircularPcmBuffer", "Reading $toRead bytes from buffer")

        // The destination buffer is correctly sized.
        val out = ByteBuffer.allocate(toRead)

        // Create a temporary view of the source buffer to control the data transfer.
        val sourceView = currentFrame.data.duplicate()
        sourceView.limit(sourceView.position() + toRead)

        // Put from the temporary view into our output buffer.
        out.put(sourceView)
        out.flip()

        // Advance the position of the original currentFrame.data
        currentFrame.data.position(currentFrame.data.position() + toRead)

        if (!currentFrame.data.hasRemaining()) {
            pendingFrame = null
        }

        // Subtract only the number of bytes actually consumed.
        availableBytes.addAndGet(-toRead)

        // Track successful reads for adaptive buffering
        val currentTime = System.currentTimeMillis()
        if (lastReadTime > 0 && (currentTime - lastReadTime) > 50) {
            // Reset underrun count on successful sustained reads
            if (underrunCount > 0) {
                underrunCount = maxOf(0, underrunCount - 1)
            }
        }
        lastReadTime = currentTime

        Log.v("CircularPcmBuffer", "Read complete. Bytes read: $toRead, Available: ${availableBytes.get()}")

        return Pair(out, currentFrame.timestamp)
    }

    /**
     * Checks if the buffer's current size is at least the specified percentage of its total capacity.
     *
     * @param percentage The percentage to check (an integer between 0 and 100).
     * @return `true` if the buffer is at least the specified percentage full, `false` otherwise.
     */
    private fun isAtLeastFull(percentage: Int): Boolean {
        require(percentage in 0..100) { "Percentage must be between 0 and 100" }
        return availableBytes.get() >= (byteCapacity * percentage / 100)
    }
}