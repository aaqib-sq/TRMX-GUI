package dev.trmx.gui

import dev.trmx.gui.model.SystemInfo
import dev.trmx.gui.net.BridgeResult
import dev.trmx.gui.net.Handshaker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakerTest {

    private val info = SystemInfo(bridge_version = "0.2.0")

    @Test
    fun `succeeds once the bridge answers`() = runTest {
        var attempts = 0
        val h = Handshaker(
            probe = { if (++attempts == 3) BridgeResult.Success(info) else BridgeResult.NetworkError(RuntimeException("refused")) },
            pollIntervalMs = 100, maxAttempts = 10)
        val r = h.awaitHandshake(delayFn = { })   // virtual time — no real sleeping
        assertTrue(r is BridgeResult.Success)
        assertTrue(attempts == 3)
    }

    @Test
    fun `auth errors surface immediately instead of burning the deadline`() = runTest {
        var attempts = 0
        val h = Handshaker(
            probe = { attempts++; BridgeResult.HttpError(401, "AUTHENTICATION_FAILED", "bad token") },
            pollIntervalMs = 100, maxAttempts = 10)
        val r = h.awaitHandshake(delayFn = { })
        assertTrue(r is BridgeResult.HttpError)
        assertTrue(attempts == 1)
    }

    @Test
    fun `wall clock deadline stops slow probes long before attempts run out`() = runTest {
        // Each clock read jumps 20 s: probes are "slow" (a frozen bridge
        // burning connect/read timeouts). 60 attempts must NOT happen —
        // the 45 s wall-clock cap ends the wait after a few probes.
        var clock = 0L
        var attempts = 0
        val h = Handshaker(
            probe = { attempts++; BridgeResult.NetworkError(RuntimeException("timeout")) },
            pollIntervalMs = 500, maxAttempts = 60, maxDurationMs = 45_000,
            nowMs = { clock += 20_000; clock })
        val r = h.awaitHandshake(delayFn = { })
        assertTrue(r is BridgeResult.NetworkError)
        assertTrue("attempts=$attempts must be far below 60", attempts in 1..5)
    }

    @Test
    fun `deadline exhausted reports the last network error`() = runTest {
        val h = Handshaker(
            probe = { BridgeResult.NetworkError(RuntimeException("refused")) },
            pollIntervalMs = 10, maxAttempts = 5)
        val r = h.awaitHandshake(delayFn = { })
        assertTrue(r is BridgeResult.NetworkError)
    }
}
