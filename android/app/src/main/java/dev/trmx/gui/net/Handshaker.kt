package dev.trmx.gui.net

/*
 * Handshake poller: after sending a START intent we cannot observe intent
 * completion (deliberately — CONTROL-PLANE.md §1), so we poll the data
 * plane until /v1/system/info answers or the deadline passes.
 *
 * The deadline is a WALL-CLOCK limit, not just an attempt count: a probe
 * against a frozen/starved bridge process can itself burn 10–20 s in
 * connect/read timeouts, and 60 such attempts once stretched a device
 * handshake past 10 minutes (on-device round 1, 2026-10-16). Attempts
 * AND elapsed time both bound the wait now.
 *
 * The delay and the clock are injectable so tests are deterministic.
 */

import dev.trmx.gui.model.SystemInfo
import kotlinx.coroutines.delay

class Handshaker(
    private val probe: suspend () -> BridgeResult<SystemInfo>,
    private val pollIntervalMs: Long = 500,
    private val maxAttempts: Int = 60,   // 30 s at the default interval
    private val maxDurationMs: Long = 45_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun awaitHandshake(
        // nullable default: default-value expressions run in a non-suspend
        // context, so a suspend callable reference can't be used there
        delayFn: (suspend (Long) -> Unit)? = null,
    ): BridgeResult<SystemInfo> {
        val wait = delayFn ?: ::delay
        val startedAt = nowMs()
        var last: BridgeResult<SystemInfo> =
            BridgeResult.NetworkError(IllegalStateException("handshake deadline passed"))
        var attempts = 0
        while (attempts < maxAttempts && nowMs() - startedAt < maxDurationMs) {
            attempts++
            val r = probe()
            when (r) {
                is BridgeResult.Success -> return r
                // auth problems will not fix themselves by retrying — surface now
                is BridgeResult.HttpError -> if (r.status == 401 || r.status == 403) return r
                else -> {}
            }
            last = r
            if (attempts < maxAttempts && nowMs() - startedAt < maxDurationMs) {
                wait(pollIntervalMs)
            }
        }
        return last
    }
}
