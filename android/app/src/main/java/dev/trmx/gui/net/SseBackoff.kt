package dev.trmx.gui.net

/*
 * Exponential backoff for SSE reconnect loops (2026-10-19 audit §6-H4).
 *
 * The /v1/events reader used to retry every 2 s forever while the bridge
 * was down — one failed connection attempt every 2 s for as long as the
 * app stayed open. This sequence starts at 2 s (a just-dropped healthy
 * stream still reconnects fast) and doubles to a 30 s cap; a stream that
 * actually connected resets it. Pure and JVM-testable; the collector owns
 * the instance, mirroring SseClient's "policy lives with the collector"
 * split.
 */
class SseBackoff(
    private val initialMs: Long = 2_000L,
    private val maxMs: Long = 30_000L,
) {
    private var currentMs: Long = initialMs

    /** Delay to wait before the next reconnect attempt; grows the sequence. */
    fun nextDelayMs(): Long {
        val delay = currentMs
        currentMs = minOf(currentMs * 2, maxMs)
        return delay
    }

    /** The stream connected — the next retry starts from [initialMs] again. */
    fun reset() {
        currentMs = initialMs
    }
}
