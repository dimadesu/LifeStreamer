/*
 * Copyright (C) 2021 Thibault B.
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
package com.dimadesu.lifestreamer.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.ts.TsExtractor.TS_PACKET_SIZE
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.extensions.connect
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import io.github.thibaultbee.srtdroid.core.models.SrtUrl
import java.io.IOException
import java.util.LinkedList
import java.util.Queue
import androidx.core.net.toUri

/**
 * ExoPlayer DataSource for SRT protocol.
 * Connects to an SRT server in caller mode and reads MPEG-TS data.
 */
@UnstableApi
class SrtDataSource : BaseDataSource(/*isNetwork*/true) {

    companion object {
        private const val PAYLOAD_SIZE = 1316
        private const val TAG = "SrtDataSource"
    }

    private val byteQueue: Queue<ByteArray> = LinkedList()
    private var socket: SrtSocket? = null
    private var srtUrl: SrtUrl? = null

    override fun open(dataSpec: DataSpec): Long {
        val srtUrl = SrtUrl(dataSpec.uri)
        if (srtUrl.transtype != null) {
            require(srtUrl.transtype == Transtype.LIVE) { "Only live mode is supported but ${srtUrl.transtype}" }
        }
        if (srtUrl.payloadSize != null) {
            require(srtUrl.payloadSize == PAYLOAD_SIZE) { "Only payload size of $PAYLOAD_SIZE is supported but ${srtUrl.payloadSize}" }
        }
        if (srtUrl.mode != null) {
            require(srtUrl.mode == SrtUrl.Mode.CALLER) { "Only caller mode is supported but ${srtUrl.mode}" }
        }

        socket = SrtSocket().apply {
            Log.i(TAG, "Connecting to ${srtUrl.hostname}:${srtUrl.port}")
            connect(srtUrl)
        }
        this.srtUrl = srtUrl
        return C.LENGTH_UNSET.toLong()
    }


    /**
     * Receives from SRT socket and feeds into a queue. Depending on the length requested
     * from ExoPlayer, that amount of bytes is polled from queue and onto the buffer with the given offset.
     *
     * You cannot directly receive at the given length from the socket, because SRT uses a
     * predetermined payload size that cannot be dynamic.
     */
    @OptIn(UnstableApi::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }

        socket?.let {
            var bytesReceived = 0
            val rcvBuffer = it.recv(PAYLOAD_SIZE)
            (0 until rcvBuffer.size / TS_PACKET_SIZE).forEach { i ->
                byteQueue.offer(
                    rcvBuffer.copyOfRange(
                        i * TS_PACKET_SIZE,
                        (i + 1) * TS_PACKET_SIZE
                    )
                )
            }
            var tmpBuffer = byteQueue.poll()
            var i = 0
            while (tmpBuffer != null) {
                System.arraycopy(tmpBuffer, 0, buffer, offset + i * TS_PACKET_SIZE, TS_PACKET_SIZE)
                bytesReceived += TS_PACKET_SIZE
                i++
                if (i * TS_PACKET_SIZE >= length) {
                    break
                }
                tmpBuffer = byteQueue.poll()
            }

            return bytesReceived
        }
        throw IOException("Couldn't read bytes at offset: $offset")
    }

    override fun getUri(): Uri {
        val srtUrl = srtUrl ?: return Uri.EMPTY
        return srtUrl.srtUri.toString().toUri()
    }

    override fun close() {
        byteQueue.clear()
        socket?.close()
        socket = null
    }
}
