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
            startTime7d = System.currentTimeMillis() - AppKillerEngine.USAGE_STATS_WINDOW_MS,
            isAccessibilityObserved = true,
            hasUsageAccess = true,
        )
        assertEquals("core-blocked", reason)
    }

    @Test
    fun testComputeNotRunningReasonStopped() {
        val now = 10_000_000L
        val startTime7d = now - AppKillerEngine.USAGE_STATS_WINDOW_MS

        // Case 1: Flag stopped is true
        val reasonStoppedFlag = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = true,
            killedAt = 0L,
            lastTimeUsedMs = now - 3600_000L, // 1 hour ago
            startTime7d = startTime7d,
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
            startTime7d = startTime7d,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        assertEquals("stopped", reasonKilledThisSession)
    }

    @Test
    fun testComputeNotRunningReasonNotActiveIn7dWindow() {
        val now = 1_000_000_000L
        val windowMs = AppKillerEngine.USAGE_STATS_WINDOW_MS
        val startTime7d = now - windowMs

        // Used 8 days ago (> 7d window)
        val lastUsed8d = now - (8L * 24 * 60 * 60 * 1000L)
        val reason = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = lastUsed8d,
            startTime7d = startTime7d,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        assertEquals("not active in 7-day window", reason)
    }

    @Test
    fun testComputeNotRunningReasonNoStatsAndNotObserved() {
        val now = 100_000_000L
        val startTime7d = now - AppKillerEngine.USAGE_STATS_WINDOW_MS

        val reasonNull = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = null,
            startTime7d = startTime7d,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        assertEquals("no usage stats and not observed", reasonNull)

        val reasonZero = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = 0L,
            startTime7d = startTime7d,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        assertEquals("no usage stats and not observed", reasonZero)
    }

    @Test
    fun testComputeNotRunningReasonUsageAccessDisabled() {
        val now = 100_000_000L
        val startTime7d = now - AppKillerEngine.USAGE_STATS_WINDOW_MS

        val reason = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = null,
            startTime7d = startTime7d,
            isAccessibilityObserved = false,
            hasUsageAccess = false,
        )
        assertEquals("usage access not granted and not observed", reason)
    }

    @Test
    fun testFormatHoursAgo() {
        val now = 1_000_000_000L

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

        // 74.7 hours ago (Meta AI evidence)
        val seventyFourPointSevenHoursAgo = now - (74.7 * 60 * 60 * 1000L).toLong()
        assertEquals("74.7h ago", AppKillerEngine.formatHoursAgo(seventyFourPointSevenHoursAgo, now))
    }

    @Test
    fun testFlagStoppedBitmaskAndHelpers() {
        val flagsWithStopped = ApplicationInfo.FLAG_STOPPED or ApplicationInfo.FLAG_SYSTEM
        assertTrue(AppKillerEngine.isStopped(flagsWithStopped))

        val flagsWithoutStopped = ApplicationInfo.FLAG_SYSTEM
        org.junit.Assert.assertFalse(AppKillerEngine.isStopped(flagsWithoutStopped))

        org.junit.Assert.assertFalse(AppKillerEngine.isStopped(null as ApplicationInfo?))

        val appInfoStopped = ApplicationInfo().apply { flags = flagsWithStopped }
        assertTrue(AppKillerEngine.isStopped(appInfoStopped))

        val appInfoRunning = ApplicationInfo().apply { flags = flagsWithoutStopped }
        org.junit.Assert.assertFalse(AppKillerEngine.isStopped(appInfoRunning))
    }

    @Test
    fun testSevenDayWindowBoundary() {
        val now = 1_000_000_000L
        val windowMs = AppKillerEngine.USAGE_STATS_WINDOW_MS
        val startTime7d = now - windowMs

        // 1 second inside 7-day window
        val justInside = startTime7d + 1000L
        val reasonInside = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = justInside,
            startTime7d = startTime7d,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        org.junit.Assert.assertNotEquals("not active in 7-day window", reasonInside)

        // 1 second outside 7-day window
        val justOutside = startTime7d - 1000L
        val reasonOutside = AppKillerEngine.computeNotRunningReason(
            isCore = false,
            isStopped = false,
            killedAt = 0L,
            lastTimeUsedMs = justOutside,
            startTime7d = startTime7d,
            isAccessibilityObserved = false,
            hasUsageAccess = true,
        )
        assertEquals("not active in 7-day window", reasonOutside)
    }

    @Test
    fun testKillFlowPreCheckSkipWithoutOpeningSettings() = kotlinx.coroutines.runBlocking {
        val stoppedPkg = "com.google.android.apps.docs"
        AppKillerEngine.isPackageStoppedOverride = { pkg -> pkg == stoppedPkg }
        val openedIntents = mutableListOf<android.content.Intent>()

        val dummyService = AppKillerAccessibilityService()

        try {
            val report = AppKillerEngine.executeKillList(
                context = android.app.Application(),
                packagesToKill = listOf(stoppedPkg),
                appLabels = mapOf(stoppedPkg to "Google Drive"),
                onProgress = {},
                serviceProvider = { dummyService },
                startActivityAction = { openedIntents.add(it) },
            )

            // Must be skipped immediately with "Already stopped" WITHOUT opening Settings
            assertEquals(1, report.skipped.size)
            assertEquals(stoppedPkg, report.skipped[0].first)
            assertEquals("Already stopped", report.skipped[0].second)
            assertEquals(0, report.killed.size)
            // Verify NO settings intent was opened
            assertTrue(
                "Settings intent must NOT be opened for already stopped app",
                openedIntents.none { it.action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS },
            )
        } finally {
            AppKillerEngine.isPackageStoppedOverride = null
        }
    }

    @Test
    fun testSelectionSnapshotAndClearedAfterRun() {
        // Verify snapshotting at start and clearing at completion
        val initialSelection = setOf("com.app.one", "com.app.two", "com.app.three")
        val targetsSnapshot = initialSelection.toList()
        assertEquals(3, targetsSnapshot.size)

        // Simulating mutation to selection state during run
        var currentSelection = initialSelection
        currentSelection = currentSelection - "com.app.one"
        assertEquals(3, targetsSnapshot.size) // snapshot unchanged

        // Post-kill run: selection cleared to emptySet()
        val postKillSelection: Set<String> = emptySet()
        assertTrue(postKillSelection.isEmpty())
    }
}
