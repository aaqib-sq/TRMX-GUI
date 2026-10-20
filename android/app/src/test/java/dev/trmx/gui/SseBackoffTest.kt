package dev.trmx.gui

import dev.trmx.gui.net.SseBackoff
import org.junit.Assert.assertEquals
import org.junit.Test

/** §6-H4: /v1/events reconnect backoff — 2 s doubling to a 30 s cap. */
class SseBackoffTest {

    @Test
    fun sequenceDoublesFrom2sTo30sCap() {
        val backoff = SseBackoff()
        val delays = (1..7).map { backoff.nextDelayMs() }
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            delays)
    }

    @Test
    fun resetRestartsAtInitialDelay() {
        val backoff = SseBackoff()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.reset()
        assertEquals(2_000L, backoff.nextDelayMs())
        assertEquals(4_000L, backoff.nextDelayMs())
    }

    @Test
    fun customBoundsAreHonoured() {
        val backoff = SseBackoff(initialMs = 500L, maxMs = 1_200L)
        assertEquals(500L, backoff.nextDelayMs())
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(1_200L, backoff.nextDelayMs())
        assertEquals(1_200L, backoff.nextDelayMs())
    }
}
