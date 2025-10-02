package com.dimadesu.lifestreamer.rtmp.audio.buffer

import android.content.Context
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer

class CustomMedia3AudioRenderer(
    context: Context,
    mediaCodecSelector: MediaCodecSelector,
    private val audioBuffer: CircularPcmBuffer
) : MediaCodecAudioRenderer(
        context,
        mediaCodecSelector,
) {
    /**
     * Creates a new, writable ByteBuffer by copying the data from a source buffer.
     *
     * This function handles both writable and read-only source buffers,
     * ensuring the returned buffer is always writable and independent.
     *
     * @param source The source ByteBuffer.
     * @return A new, writable ByteBuffer containing a copy of the source's data.
     */
    fun copyToWritableByteBuffer(source: ByteBuffer): ByteBuffer {
        // 1. Get the number of remaining bytes in the source buffer.
        // This is the amount of data we need to copy.
        val sizeToCopy = source.remaining()

        // 2. Allocate a new ByteBuffer with enough capacity to hold the data.
        // This new buffer will be writable.
        val writableCopy = ByteBuffer.allocate(sizeToCopy)

        // 3. Get the original position of the source buffer so we can restore it later.
        val originalPosition = source.position()

        // 4. Copy the data from the source to the new buffer.
        // The put() method automatically advances the position of both buffers.
        writableCopy.put(source)

        // 5. Flip the new buffer to prepare it for reading.
        // This sets the position to 0 and the limit to the end of the written data.
        writableCopy.flip()

        // 6. Restore the position of the original source buffer.
        // This is a good practice to prevent side effects in the calling code.
        source.position(originalPosition)

        return writableCopy
    }

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codecAdapter: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format
    ): Boolean {
        var copy = ByteBuffer.allocate(0)
        var originalPosition = 0

        if (buffer != null) {

            // Copy bytes synchronously before releasing the codec buffer
            originalPosition = buffer.position()
//            val len = buffer.remaining()
//            val copy = ByteBuffer.allocate(len)
//            copy.put(buffer)
//            buffer.position(originalPosition)
//            copy.flip()

            copy = buffer.asReadOnlyBuffer()
            copy.position(originalPosition)
        }

        // Continue normal rendering path (lets ExoPlayer release the buffer)
        val isFullyProcessed =  super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codecAdapter,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs,
            isDecodeOnlyBuffer,
            isLastBuffer,
            format
        )

        if (buffer != null) {

            val bytesRead = buffer.position() - originalPosition

            if (bytesRead > 0) {

                Log.i("XXX", "writing bytes $bytesRead")

                if (!isFullyProcessed) {
                   copy.limit(originalPosition + bytesRead)
                }

               audioBuffer.writeFrame(copyToWritableByteBuffer(copy), bufferPresentationTimeUs)
           }
       }

       return isFullyProcessed
   }
}