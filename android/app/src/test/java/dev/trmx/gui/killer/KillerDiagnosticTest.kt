package dev.trmx.gui.killer

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KillerDiagnosticTest {

    @Test
    fun testComputeNotRunningReasonCoreBlocked() {
        val reason = AppKillerEngine.computeNotRunningReason(
            isCore = true,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = System.currentTimeMillis() - 1000L,
            startTime24h = System.currentTimeMillis() - AppKillerEngine.USAGE_STATS_WINDOW_MS,
            isAccessibilityObserved = true,
            hasUsageAccess = true,
        )
        assertEquals("core-blocked", reason)
    }

    @Test
    fun testComputeNotRunningReasonStopped() {
        val now = 10_000_000L
        val startTime24h = now - AppKillerEngine.USAGE_STATS_WINDOW_MS

        // Case 1: Flag stopped is true
        val reasonStoppedFlag = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = true,
            killedAt = 0L,
            lastTimeUsedMs = now - 3600_000L, // 1 hour ago
            startTime24h = startTime24h,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        assertEquals("stopped", reasonStoppedFlag)

        // Case 2: Killed in current session (killedAt >= lastTimeUsedMs)
        val reasonKilledThisSession = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = now - 500L,
            lastTimeUsedMs = now - 1000L,
            startTime24h = startTime24h,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        assertEquals("stopped", reasonKilledThisSession)
    }

    @Test
    fun testComputeNotRunningReasonNotActiveIn24hWindow() {
        val now = 100_000_000L
        val windowMs = AppKillerEngine.USAGE_STATS_WINDOW_MS
        val startTime24h = now - windowMs

        // Used 30 hours ago (> 24h window)
        val lastUsed30h = now - (30L * 60 * 60 * 1000L)
        val reason = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = lastUsed30h,
            startTime24h = startTime24h,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        assertEquals("not active in 24h window", reason)
    }

    @Test
    fun testComputeNotRunningReasonNoStatsAndNotObserved() {
        val now = 100_000_000L
        val startTime24h = now - AppKillerEngine.USAGE_STATS_WINDOW_MS

        val reasonNull = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = null,
            startTime24h = startTime24h,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        assertEquals("no usage stats and not observed", reasonNull)

        val reasonZero = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = 0L,
            startTime24h = startTime24h,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        assertEquals("no usage stats and not observed", reasonZero)
    }

    @Test
    fun testComputeNotRunningReasonUsageAccessDisabled() {
        val now = 100_000_000L
        val startTime24h = now - AppKillerEngine.USAGE_STATS_WINDOW_MS

        val reason = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = null,
            startTime24h = startTime24h,
            isAccessibilityObserved = false,
            hasUsageAccess = false,
        )
        assertEquals("usage access not granted and not observed", reason)
    }

    @Test
    fun testFormatHoursAgo() {
        val now = 100_000_000L

        assertEquals("no stats", AppKillerEngine.formatHoursAgo(null, now))
        assertEquals("no stats", AppKillerEngine.formatHoursAgo(0L, now))
        assertEquals("no stats", AppKillerEngine.formatHoursAgo(-500L, now))

        // 15 minutes ago
        val fifteenMinsAgo = now - (15L * 60 * 1000L)
        assertEquals("<1h ago (15m)", AppKillerEngine.formatHoursAgo(fifteenMinsAgo, now))

        // 2 hours ago
        val twoHoursAgo = now - (2L * 60 * 60 * 1000L)
        assertEquals("2.0h ago", AppKillerEngine.formatHoursAgo(twoHoursAgo, now))

        // 24.5 hours ago
        val twentyFourPointFiveHoursAgo = now - (24.5 * 60 * 60 * 1000L).toLong()
        assertEquals("24.5h ago", AppKillerEngine.formatHoursAgo(twentyFourPointFiveHoursAgo, now))
    }

    @Test
    fun testFlagStoppedBitmask() {
        val flagsWithStopped = ApplicationInfo.FLAG_STOPPED or ApplicationInfo.FLAG_SYSTEM
        assertTrue((flagsWithStopped and ApplicationInfo.FLAG_STOPPED) != 0)

        val flagsWithoutStopped = ApplicationInfo.FLAG_SYSTEM
        assertTrue((flagsWithoutStopped and ApplicationInfo.FLAG_STOPPED) == 0)
    }
}
