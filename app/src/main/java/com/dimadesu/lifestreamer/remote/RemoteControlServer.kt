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
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.matchingPreset
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
import java.util.concurrent.CopyOnWriteArrayList
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
        fun power(): RemoteDto.PowerDto
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
     * An open event stream used to hold a thread each, which starved command handling with only
     * six request threads. Now a stream costs no thread at all: state is pushed when it changes
     * and a single heartbeat thread keeps every connection warm.
     */
    private val eventClients = CopyOnWriteArrayList<EventClient>()
    private var heartbeatThread: Thread? = null

    /**
     * Every outbound push goes through here.
     *
     * The pushes are triggered by flow collectors in the service, which run on the main thread,
     * and writing to a socket there throws NetworkOnMainThreadException. That was being swallowed
     * by the runCatching around the write, so the page was simply dropped on the first push.
     * One thread also keeps pushes in order.
     */
    private val pushExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RemoteControlPush").apply { isDaemon = true }
    }

    private val revision = AtomicLong(0)

    /**
     * The last state we sent, minus its revision, so an unchanged state is not re-sent. The page
     * rebuilds parts of its DOM on every state, which would fight a drag or a focused control.
     */
    @Volatile
    private var lastStateFingerprint: String? = null

    /**
     * One connected page. Owns its socket, and serialises its own writes: the heartbeat thread,
     * a state push and a message broadcast can all reach the same stream, and a BufferedOutputStream
     * interleaved by two threads corrupts the SSE framing so the browser's JSON.parse throws.
     */
    private class EventClient(private val socket: Socket, private val output: BufferedOutputStream) {
        private val writeLock = Any()

        fun writeRaw(text: String) {
            synchronized(writeLock) {
                output.write(text.toByteArray())
                output.flush()
            }
        }

        fun send(event: String, data: String) = writeRaw("event: $event\ndata: $data\n\n")

        fun ping() = writeRaw(":ping\n\n")

        fun close() {
            runCatching { output.close() }
            runCatching { socket.close() }
        }
    }

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

        heartbeatThread = Thread({
            while (isRunning.get()) {
                runCatching { Thread.sleep(PING_INTERVAL_MS) }.onFailure { return@Thread }
                // A comment keeps NAT open and is how a browser that went away is noticed.
                eventClients.forEach { client ->
                    runCatching { client.ping() }.onFailure { dropClient(client, "ping failed", it) }
                }
            }
        }, "RemoteControlHeartbeat").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        Log.i(TAG, "Stopping the remote control")

        runCatching { serverSocket?.close() }
        serverSocket = null

        eventClients.forEach { runCatching { it.close() } }
        eventClients.clear()
        lastStateFingerprint = null

        auth.revokeAll()
        heartbeatThread?.interrupt()
        heartbeatThread = null
        pushExecutor.shutdownNow()
        requestPool.shutdownNow()
        runCatching { requestPool.awaitTermination(1, TimeUnit.SECONDS) }
        acceptThread = null
    }

    // region connection handling

    private fun serveConnection(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val address = socket.inetAddress?.hostAddress ?: "unknown"

        // An event stream outlives this method, so the socket must not be closed on the way out.
        // Without this the stream was closed microseconds after being opened, the browser saw a
        // connection accepted and dropped with no HTTP response at all, and reconnected forever.
        var handedOff = false

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream())

            // Keep-alive: a browser reuses the connection for every command.
            while (isRunning.get() && !socket.isClosed) {
                val request = readRequest(reader) ?: break
                when (route(request, address, socket, output)) {
                    RouteResult.REUSE -> Unit
                    RouteResult.CLOSE -> break
                    RouteResult.HANDED_OFF -> {
                        handedOff = true
                        break
                    }
                }
            }
        } catch (_: IOException) {
            // Normal: the browser went away.
        } catch (t: Throwable) {
            Log.w(TAG, "Connection from $address failed: ${t.message}")
        } finally {
            if (!handedOff) {
                runCatching { socket.close() }
            }
        }
    }

    private enum class RouteResult { REUSE, CLOSE, HANDED_OFF }

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

    private fun route(
        request: Request,
        address: String,
        socket: Socket,
        output: BufferedOutputStream
    ): RouteResult {
        if (request.path == "/" || request.path == "/index.html") {
            respond(output, 200, "text/html; charset=utf-8", pageBytes, "Cache-Control: no-store")
            return RouteResult.REUSE
        }

        if (request.path == "/favicon.ico") {
            respond(output, 204, "text/plain", ByteArray(0))
            return RouteResult.REUSE
        }

        if (request.path == "/api/auth") {
            handleAuth(request, address, output)
            return RouteResult.REUSE
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
            return RouteResult.REUSE
        }

        if (request.path == "/api/events") {
            return if (startEventStream(socket, output, address)) {
                RouteResult.HANDED_OFF
            } else {
                RouteResult.CLOSE
            }
        }

        handleCommand(request, output)
        return RouteResult.REUSE
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

    private fun handleCommand(request: Request, output: BufferedOutputStream) {
        when (request.path) {
            "/api/state" -> respondJson(output, 200, buildState())

            "/api/layout/preset" -> {
                val id = parse<RemoteDto.PresetRequest>(request.body)?.presetId
                val known = id != null && controller.presets.any { it.id == id }
                if (known) {
                    controller.applyPreset(id!!)
                    respondJson(output, 200, RemoteDto.OkResponse(true))
                } else {
                    // Answering ok to a command that did nothing is how a field-name mismatch or a
                    // stale page goes unnoticed.
                    respondJson(output, 400, RemoteDto.OkResponse(false, "Unknown preset: $id"))
                }
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
                    respondJson(output, 200, RemoteDto.OkResponse(true))
                } else {
                    respondJson(output, 400, RemoteDto.OkResponse(false, "Missing layer or visible"))
                }
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
                    else -> {
                        respondJson(output, 400, RemoteDto.OkResponse(false, "Missing ratio or factor"))
                        return
                    }
                }
                respondJson(output, 200, RemoteDto.OkResponse(true))
            }

            "/api/mute" -> {
                val wanted = parse<RemoteDto.MuteRequest>(request.body)?.muted
                if (wanted == null) {
                    respondJson(output, 400, RemoteDto.OkResponse(false, "Missing muted"))
                } else {
                    hooks.setMuted(wanted)
                    respondJson(output, 200, RemoteDto.OkResponse(true))
                }
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
    }

    private fun round2(value: Float): Float = Math.round(value * 100f) / 100f

    private inline fun <reified T> parse(body: String): T? =
        runCatching { gson.fromJson(body, T::class.java) }.getOrNull()

    // endregion

    // region state and events

    fun buildState(): RemoteDto.StateDto {
        val composite = controller.composite
        val layout = composite?.layoutFlow?.value
        val camerasInUse = controller.cameraIdsInUse()

        // Zoom comes from a cache and a refresh is kicked off out of band. Reading it inline used
        // to mean a suspending camera call per layer inside runBlocking on this very thread, so a
        // single stalled read froze every connected page on a stale snapshot.
        controller.refreshZoomAsync()

        val layers = layout?.layers?.sortedBy { it.z }?.map { layer ->
            val cameraId = (composite.childSource(layer.id) as? ICameraSource)?.cameraId
            val zoom = controller.cachedZoomState(layer.id)
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
                // Same reason as the thermal headroom: an unrounded ratio jitters and would make
                // every snapshot compare different.
                zoom = zoom?.let {
                    RemoteDto.ZoomDto(
                        round2(it.min), round2(it.max), round2(it.ratio), cameraId
                    )
                }
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
            activePresetId = layout?.matchingPreset(controller.presets)?.id,
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
            thermal = hooks.thermal(),
            power = hooks.power()
        )
    }

    /**
     * Opens an event stream on this socket and takes ownership of it.
     *
     * The headers are written on the caller's thread, synchronously. The previous version handed
     * the socket to a worker pool and returned, and the caller's `finally` then closed the socket
     * before the worker had written a single byte -- so the browser saw a connection accepted and
     * dropped with no response, and reconnected every three seconds forever.
     *
     * @return true when the stream is open and the socket now belongs to [eventClients].
     */
    private fun startEventStream(
        socket: Socket,
        output: BufferedOutputStream,
        address: String
    ): Boolean {
        // A non-200 answer puts EventSource into CLOSED for good: it never retries. So the oldest
        // stream is dropped to make room instead of refusing the new one.
        while (eventClients.size >= MAX_EVENT_CLIENTS) {
            val oldest = eventClients.firstOrNull() ?: break
            dropClient(oldest, "making room for a new client")
        }

        val client = EventClient(socket, output)
        return try {
            client.writeRaw(
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "X-Accel-Buffering: no\r\n" +
                        "Connection: keep-alive\r\n\r\n" +
                        "retry: 3000\n\n"
            )
            // Nothing is read from this socket again, and a read timeout must not apply to a
            // connection that is meant to stay open indefinitely.
            runCatching { socket.soTimeout = 0 }
            eventClients.add(client)
            client.send("state", gson.toJson(buildState()))
            Log.i(TAG, "Event stream opened for $address (${eventClients.size} client(s))")
            true
        } catch (t: Throwable) {
            // Never silent again: swallowing this is what hid the bug above.
            Log.w(TAG, "Event stream for $address failed to open: ${t.message}", t)
            eventClients.remove(client)
            client.close()
            false
        }
    }

    private fun dropClient(client: EventClient, why: String, cause: Throwable? = null) {
        if (eventClients.remove(client)) {
            Log.i(TAG, "Event stream closed ($why), ${eventClients.size} client(s) left", cause)
        }
        client.close()
    }

    /**
     * Pushes the state to every page, but only when it actually differs from the last one sent.
     *
     * State used to go out on a two second timer inside each stream, which meant every remote
     * action sat up to two seconds with no feedback and the page could not tell "applied" from
     * "ignored". Now the service pushes on change and this stays quiet otherwise -- the page
     * rebuilds parts of its DOM on each state, which would fight a drag or a focused control.
     */
    fun broadcastState(force: Boolean = false) {
        if (eventClients.isEmpty()) {
            lastStateFingerprint = null
            return
        }
        runCatching { pushExecutor.execute { pushState(force) } }
    }

    private fun pushState(force: Boolean) {
        if (eventClients.isEmpty()) {
            lastStateFingerprint = null
            return
        }

        val candidate = buildState()
        val fingerprint = gson.toJson(candidate.copy(revision = 0))
        if (!force && fingerprint == lastStateFingerprint) {
            return
        }
        lastStateFingerprint = fingerprint

        val payload = gson.toJson(candidate.copy(revision = revision.incrementAndGet()))
        eventClients.forEach { client ->
            runCatching { client.send("state", payload) }
                .onFailure { dropClient(client, "state push failed", it) }
        }
    }

    /**
     * Pushes a message to every connected page, so a refusal reaches the remote operator rather
     * than looking like a tap that did nothing.
     */
    fun broadcastMessage(text: String) {
        if (eventClients.isEmpty()) {
            return
        }
        val payload = gson.toJson(RemoteDto.MessageDto(text))
        runCatching {
            pushExecutor.execute {
                eventClients.forEach { client ->
                    runCatching { client.send("message", payload) }
                        .onFailure { dropClient(client, "message push failed", it) }
                }
            }
        }
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

        private const val REQUEST_THREADS = 12

        private const val MAX_EVENT_CLIENTS = 4

        /**
         * How long an idle keep-alive connection may hold a request thread.
         *
         * A browser opens up to six connections per host and each one parks a thread in readLine
         * until this expires, so at thirty seconds a single tab could hold the whole pool and
         * later commands sat in the queue unanswered -- which looked like commands being lost.
         * Event streams set their own timeout to zero once handed off.
         */
        private const val SOCKET_TIMEOUT_MS = 5_000

        /** Keepalive only; state is pushed when it changes, not on this tick. */
        private const val PING_INTERVAL_MS = 15_000L
    }
}
