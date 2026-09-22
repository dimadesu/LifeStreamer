/*
 * Copyright (C) 2026 dimadesu
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
package com.dimadesu.lifestreamer.remote

import android.content.Context
import android.util.Log
import com.dimadesu.lifestreamer.composition.CompositionController
import com.google.gson.Gson
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A small HTTP server that serves the remote control page and takes its commands.
 *
 * Hand-rolled rather than pulling in a dependency, because the page has to be served over HTTP
 * anyway and, once that exists, Server-Sent Events are about fifteen lines. A WebSocket on the
 * same port would mean writing the RFC 6455 upgrade by hand, or a second port; and it would buy
 * nothing here, since commands only ever travel browser to phone and state only ever travels
 * back — which is exactly the shape of SSE plus POST.
 */
class RemoteControlServer(
    private val context: Context,
    private val port: Int,
    private val controller: CompositionController,
    private val hooks: Hooks
) {
    /**
     * The parts of the app the server needs that are not the composition.
     */
    interface Hooks {
        fun isStreaming(): Boolean
        fun isMuted(): Boolean
        fun setMuted(muted: Boolean)
        fun thermal(): RemoteDto.ThermalDto?
        fun setPreviewEnabled(enabled: Boolean)
        fun setPreviewShortEdge(shortEdge: Int?)
        fun setPreviewFpsCap(maxFps: Int?)
        fun onLockdown()
    }

    private val gson = Gson()
    val auth = RemoteAuth { hooks.onLockdown() }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val requestPool = Executors.newFixedThreadPool(REQUEST_THREADS)

    /**
     * SSE connections hold a thread for their whole life, so they get their own pool. Sharing
     * one would let a few stalled browsers starve command handling.
     */
    private val eventPool = Executors.newCachedThreadPool()
    private val eventClients = Collections.synchronizedList(mutableListOf<BufferedOutputStream>())

    private val revision = AtomicLong(0)

    private val pageBytes: ByteArray by lazy {
        // Assets are stored compressed, so available() is not a length: read it all.
        context.assets.open("remote/index.html").use { it.readBytes() }
    }

    fun start() {
        if (isRunning.getAndSet(true)) {
            return
        }

        acceptThread = Thread({
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                serverSocket = socket
                Log.i(TAG, "Remote control listening on port $port")

                while (isRunning.get()) {
                    val client = socket.accept()
                    requestPool.execute { serveConnection(client) }
                }
            } catch (e: SocketException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Accept loop failed: ${e.message}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Remote control could not start on port $port: ${t.message}", t)
            }
        }, "RemoteControlAccept").also { it.start() }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        Log.i(TAG, "Stopping the remote control")

        runCatching { serverSocket?.close() }
        serverSocket = null

        synchronized(eventClients) {
            eventClients.forEach { runCatching { it.close() } }
            eventClients.clear()
        }

        auth.revokeAll()
        requestPool.shutdownNow()
        eventPool.shutdownNow()
        runCatching { requestPool.awaitTermination(1, TimeUnit.SECONDS) }
        runCatching { eventPool.awaitTermination(1, TimeUnit.SECONDS) }
        acceptThread = null
    }

    // region connection handling

    private fun serveConnection(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val address = socket.inetAddress?.hostAddress ?: "unknown"

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream())

            // Keep-alive: a browser reuses the connection for every command.
            while (isRunning.get() && !socket.isClosed) {
                val request = readRequest(reader) ?: break
                val keepOpen = route(request, address, output)
                if (!keepOpen) {
                    break
                }
            }
        } catch (_: IOException) {
            // Normal: the browser went away.
        } catch (t: Throwable) {
            Log.w(TAG, "Connection from $address failed: ${t.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readRequest(reader: BufferedReader): Request? {
        val requestLine = reader.readLine() ?: return null
        if (requestLine.isBlank()) {
            return null
        }

        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            return null
        }

        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
            .split('&')
            .filter { it.isNotBlank() }
            .associate {
                val key = it.substringBefore('=')
                val value = it.substringAfter('=', "")
                key to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                break
            }
            val name = line.substringBefore(':').trim().lowercase()
            headers[name] = line.substringAfter(':').trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buffer = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read)
        } else {
            ""
        }

        return Request(method, path, query, headers, body)
    }

    /**
     * @return whether the connection may be reused. SSE hands the socket off and returns false.
     */
    private fun route(request: Request, address: String, output: BufferedOutputStream): Boolean {
        if (request.path == "/" || request.path == "/index.html") {
            respond(output, 200, "text/html; charset=utf-8", pageBytes, "Cache-Control: no-store")
            return true
        }

        if (request.path == "/favicon.ico") {
            respond(output, 204, "text/plain", ByteArray(0))
            return true
        }

        if (request.path == "/api/auth") {
            handleAuth(request, address, output)
            return true
        }

        // Everything below needs a token. EventSource cannot set headers, so the stream endpoint
        // takes it as a query parameter — acceptable only because the transport is already
        // cleartext, which is stated plainly in the app.
        val token = request.headers["authorization"]
            ?.removePrefix("Bearer ")
            ?.trim()
            ?: request.query["token"]

        if (!auth.isTokenValid(token)) {
            respondJson(output, 401, RemoteDto.ErrorResponse("unauthorized"))
            return true
        }

        if (request.path == "/api/events") {
            startEventStream(output)
            return false
        }

        return handleCommand(request, output)
    }

    private fun handleAuth(request: Request, address: String, output: BufferedOutputStream) {
        val lockout = auth.lockoutRemainingMs(address)
        if (lockout > 0) {
            respondJson(output, 429, RemoteDto.ErrorResponse("locked", lockout))
            return
        }

        val pin = runCatching {
            gson.fromJson(request.body, RemoteDto.AuthRequest::class.java)?.pin
        }.getOrNull().orEmpty()

        val token = auth.authenticate(pin, address)
        if (token == null) {
            // Constant delay on every failure, on top of the constant-time comparison.
            Thread.sleep(RemoteAuth.FAILURE_DELAY_MS)
            respondJson(output, 401, RemoteDto.ErrorResponse("bad_pin"))
            return
        }

        respondJson(
            output, 200,
            RemoteDto.AuthResponse(token, RemoteAuth.SESSION_IDLE_MS / 1000)
        )
    }

    private fun handleCommand(request: Request, output: BufferedOutputStream): Boolean {
        when (request.path) {
            "/api/state" -> {
                respondJson(output, 200, buildState())
                return true
            }

            "/api/layout/preset" -> {
                val body = parse<RemoteDto.PresetRequest>(request.body)
                body?.presetId?.let { controller.applyPreset(it) }
                respondJson(output, 200, RemoteDto.OkResponse(true))
            }

            "/api/layout/swap" -> {
                controller.swapLayers()
                respondJson(output, 200, RemoteDto.OkResponse(true))
            }

            "/api/layer/rect" -> {
                val body = parse<RemoteDto.LayerRectRequest>(request.body)
                val id = body?.layerId
                if (id != null && body.left != null && body.top != null &&
                    body.right != null && body.bottom != null
                ) {
                    controller.setLayerRect(
                        id,
                        io.github.thibaultbee.streampack.core.elements.processing.video.composition
                            .LayerRect(body.left, body.top, body.right, body.bottom)
                    )
                }
                // No body: this is the hot path of a drag.
                respond(output, 204, "text/plain", ByteArray(0))
            }

            "/api/layer/commit" -> {
                parse<RemoteDto.LayerRequest>(request.body)?.layerId
                    ?.let { controller.commitLayerGeometry(it) }
                respond(output, 204, "text/plain", ByteArray(0))
            }

            "/api/layer/visible" -> {
                val body = parse<RemoteDto.LayerVisibleRequest>(request.body)
                if (body?.layerId != null && body.visible != null) {
                    controller.setLayerVisible(body.layerId, body.visible)
                }
                respondJson(output, 200, RemoteDto.OkResponse(true))
            }

            "/api/layer/primary" -> {
                parse<RemoteDto.LayerRequest>(request.body)?.layerId
                    ?.let { controller.setPrimaryLayer(it) }
                respondJson(output, 200, RemoteDto.OkResponse(true))
            }

            "/api/layer/camera" -> {
                val body = parse<RemoteDto.LayerCameraRequest>(request.body)
                if (body?.layerId == null || body.cameraId == null) {
                    respondJson(output, 200, RemoteDto.OkResponse(false, "Missing layer or camera"))
                } else {
                    // Structural, so it is awaited: the answer says whether the hardware allowed
                    // it, which is what the page shows instead of doing nothing.
                    val result = kotlinx.coroutines.runBlocking {
                        controller.setLayerCamera(body.layerId, body.cameraId)
                    }
                    respondJson(
                        output, 200,
                        RemoteDto.OkResponse(
                            result.isSuccess,
                            result.exceptionOrNull()?.message
                        )
                    )
                }
            }

            "/api/zoom" -> {
                val body = parse<RemoteDto.ZoomRequest>(request.body)
                when {
                    body?.ratio != null -> controller.setZoom(body.layerId, body.ratio)
                    body?.factor != null -> controller.nudgeZoom(body.layerId, body.factor)
                }
                respondJson(output, 200, RemoteDto.OkResponse(true))
            }

            "/api/mute" -> {
                parse<RemoteDto.MuteRequest>(request.body)?.muted?.let { hooks.setMuted(it) }
                respondJson(output, 200, RemoteDto.OkResponse(true))
            }

            "/api/preview" -> {
                parse<RemoteDto.PreviewRequest>(request.body)?.enabled
                    ?.let { hooks.setPreviewEnabled(it) }
                respondJson(output, 200, RemoteDto.OkResponse(true))
            }

            "/api/preview/resolution" -> {
                val body = parse<RemoteDto.PreviewResolutionRequest>(request.body)
                hooks.setPreviewShortEdge(body?.shortEdge?.takeIf { it > 0 })
                respondJson(output, 200, RemoteDto.OkResponse(true))
            }

            "/api/preview/fps" -> {
                val body = parse<RemoteDto.PreviewFpsRequest>(request.body)
                hooks.setPreviewFpsCap(body?.maxFps?.takeIf { it > 0 })
                respondJson(output, 200, RemoteDto.OkResponse(true))
            }

            "/api/logout" -> {
                auth.revokeAll()
                respond(output, 204, "text/plain", ByteArray(0))
            }

            else -> respondJson(output, 404, RemoteDto.ErrorResponse("not_found"))
        }
        return true
    }

    private inline fun <reified T> parse(body: String): T? =
        runCatching { gson.fromJson(body, T::class.java) }.getOrNull()

    // endregion

    // region state and events

    fun buildState(): RemoteDto.StateDto {
        val composite = controller.composite
        val layout = composite?.layoutFlow?.value
        val camerasInUse = controller.cameraIdsInUse()

        val layers = layout?.layers?.sortedBy { it.z }?.map { layer ->
            val cameraId = (composite.childSource(layer.id) as? ICameraSource)?.cameraId
            val zoom = kotlinx.coroutines.runBlocking { controller.zoomState(layer.id) }
            RemoteDto.LayerDto(
                id = layer.id,
                label = controller.layerLabel(layer.id),
                z = layer.z,
                left = layer.rect.left,
                top = layer.rect.top,
                right = layer.rect.right,
                bottom = layer.rect.bottom,
                visible = layer.visible,
                primary = layer.id == layout.primaryLayer?.id,
                cameraId = cameraId,
                zoom = zoom?.let { RemoteDto.ZoomDto(it.min, it.max, it.ratio) }
            )
        } ?: emptyList()

        return RemoteDto.StateDto(
            revision = revision.incrementAndGet(),
            composition = composite != null,
            streaming = hooks.isStreaming(),
            muted = hooks.isMuted(),
            canvasWidth = composite?.canvasSize?.width ?: 0,
            canvasHeight = composite?.canvasSize?.height ?: 0,
            presets = controller.presets.map { RemoteDto.PresetDto(it.id, it.name) },
            layers = layers,
            cameras = controller.cameras.value.map { camera ->
                RemoteDto.CameraDto(
                    id = camera.id,
                    name = camera.displayName,
                    facing = camera.facing,
                    usedByLayerId = layout?.layers
                        ?.firstOrNull {
                            (composite?.childSource(it.id) as? ICameraSource)?.cameraId == camera.id
                        }?.id
                        ?.takeIf { camerasInUse.contains(camera.id) }
                )
            },
            thermal = hooks.thermal()
        )
    }

    private fun startEventStream(output: BufferedOutputStream) {
        eventPool.execute {
            try {
                if (eventClients.size >= MAX_EVENT_CLIENTS) {
                    respondJson(output, 503, RemoteDto.ErrorResponse("too_many_clients"))
                    return@execute
                }

                output.write(
                    ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Cache-Control: no-store\r\n" +
                            "Connection: keep-alive\r\n\r\n" +
                            "retry: 3000\n\n").toByteArray()
                )
                output.flush()
                eventClients.add(output)

                sendEvent(output, "state", gson.toJson(buildState()))

                while (isRunning.get()) {
                    Thread.sleep(PING_INTERVAL_MS)
                    // A comment keeps NAT open and detects a browser that went away.
                    output.write(":ping\n\n".toByteArray())
                    output.flush()
                    sendEvent(output, "state", gson.toJson(buildState()))
                }
            } catch (_: Throwable) {
                // The browser went away.
            } finally {
                eventClients.remove(output)
                runCatching { output.close() }
            }
        }
    }

    /**
     * Pushes a message to every connected page, so a refusal reaches the remote operator rather
     * than looking like a tap that did nothing.
     */
    fun broadcastMessage(text: String) {
        val payload = gson.toJson(RemoteDto.MessageDto(text))
        synchronized(eventClients) {
            eventClients.toList().forEach { client ->
                runCatching { sendEvent(client, "message", payload) }
                    .onFailure { eventClients.remove(client) }
            }
        }
    }

    private fun sendEvent(output: BufferedOutputStream, event: String, data: String) {
        output.write("event: $event\ndata: $data\n\n".toByteArray())
        output.flush()
    }

    // endregion

    // region responses

    private fun respondJson(output: BufferedOutputStream, status: Int, body: Any) {
        respond(output, status, "application/json; charset=utf-8", gson.toJson(body).toByteArray())
    }

    private fun respond(
        output: BufferedOutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        vararg extraHeaders: String
    ) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            401 -> "Unauthorized"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            503 -> "Service Unavailable"
            else -> "OK"
        }

        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            extraHeaders.forEach { append("$it\r\n") }
            append("Connection: keep-alive\r\n\r\n")
        }

        output.write(header.toByteArray())
        if (body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    // endregion

    companion object {
        private const val TAG = "RemoteControlServer"

        private const val REQUEST_THREADS = 6
        private const val MAX_EVENT_CLIENTS = 4
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val PING_INTERVAL_MS = 2_000L
    }
}
