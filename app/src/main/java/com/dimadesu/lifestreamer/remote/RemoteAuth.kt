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

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * PIN check and session tokens for the remote control.
 *
 * **Deliberately not a challenge/response like Moblink's.** `crypto.subtle` is undefined on a
 * plain `http://192.168.x.x` origin — the Web Crypto API requires a secure context — so hashing
 * in the page would mean shipping a hand-written SHA-256 in JavaScript. And the gain would be
 * close to nil: the transport is cleartext either way, so whatever session credential follows the
 * handshake is sniffable. It would protect the PIN and hand over the session in the next packet.
 *
 * So: the PIN crosses once in the clear, then a random bearer token. The honest mitigation is
 * saying plainly in the UI that anyone on this network who knows the PIN can control the stream.
 */
class RemoteAuth(private val onLockdown: () -> Unit) {

    private class Session(val token: String, var lastSeenMs: Long)

    private class Attempts(var count: Int = 0, var lockedUntilMs: Long = 0L)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val attemptsByAddress = ConcurrentHashMap<String, Attempts>()
    private val random = SecureRandom()

    @Volatile
    var pin: String = ""

    private var globalFailures = 0
    private var globalWindowStartMs = 0L

    /**
     * Six digits from [SecureRandom].
     *
     * Generated per install and never given a default in `strings.xml` — a default there would
     * ship the same PIN to everybody. This is the one place where breaking the `default_X`
     * convention is the correct call.
     */
    fun generatePin(): String = (1..PIN_LENGTH)
        .map { random.nextInt(10) }
        .joinToString("")

    fun isPinValid(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it.isDigit() }

    /**
     * @return a token when the PIN is right, or null. Callers must apply [failureDelayMs] before
     * answering a failure, which also flattens timing.
     */
    fun authenticate(candidate: String, address: String): String? {
        val attempts = attemptsByAddress.getOrPut(address) { Attempts() }
        val now = SystemClock.elapsedRealtime()

        if (now < attempts.lockedUntilMs) {
            return null
        }

        val expected = pin
        val ok = expected.isNotEmpty() && MessageDigest.isEqual(
            candidate.toByteArray(),
            expected.toByteArray()
        )

        if (!ok) {
            attempts.count++
            attempts.lockedUntilMs = now + lockoutFor(attempts.count)
            registerGlobalFailure(now)
            Log.w(TAG, "Wrong PIN from $address (attempt ${attempts.count})")
            return null
        }

        attempts.count = 0
        attempts.lockedUntilMs = 0L

        val token = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

        if (sessions.size >= MAX_SESSIONS) {
            sessions.entries.minByOrNull { it.value.lastSeenMs }?.let { sessions.remove(it.key) }
        }
        sessions[token] = Session(token, now)
        Log.i(TAG, "Remote control session opened for $address")
        return token
    }

    fun lockoutRemainingMs(address: String): Long {
        val attempts = attemptsByAddress[address] ?: return 0L
        return (attempts.lockedUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    fun isTokenValid(token: String?): Boolean {
        if (token.isNullOrEmpty()) {
            return false
        }
        val session = sessions[token] ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - session.lastSeenMs > SESSION_IDLE_MS) {
            sessions.remove(token)
            return false
        }
        session.lastSeenMs = now
        return true
    }

    fun revokeAll() {
        sessions.clear()
    }

    /**
     * An attacker cycling source ports would sidestep the per-address lockout, so there is a
     * global backstop that simply stops the server.
     */
    private fun registerGlobalFailure(nowMs: Long) {
        if (nowMs - globalWindowStartMs > GLOBAL_WINDOW_MS) {
            globalWindowStartMs = nowMs
            globalFailures = 0
        }
        globalFailures++
        if (globalFailures >= GLOBAL_FAILURE_LIMIT) {
            Log.e(TAG, "Too many wrong PINs; shutting the remote control down")
            onLockdown()
        }
    }

    private fun lockoutFor(failureCount: Int): Long = when {
        failureCount < 5 -> 0L
        failureCount < 10 -> 30_000L
        else -> (5 * 60_000L * (1L shl ((failureCount - 10) / 5))).coerceAtMost(30 * 60_000L)
    }

    companion object {
        private const val TAG = "RemoteAuth"

        const val PIN_LENGTH = 6
        const val SESSION_IDLE_MS = 8 * 60 * 60_000L

        /** Also flattens timing differences, on top of the constant-time comparison. */
        const val FAILURE_DELAY_MS = 300L

        private const val TOKEN_BYTES = 32
        private const val MAX_SESSIONS = 8
        private const val GLOBAL_WINDOW_MS = 10 * 60_000L
        private const val GLOBAL_FAILURE_LIMIT = 20
    }
}
