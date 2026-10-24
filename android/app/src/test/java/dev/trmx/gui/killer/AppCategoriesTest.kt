package dev.trmx.gui.killer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCategoriesTest {

    @Test
    fun testCorePackagesAreNeverKillable() {
        val critical = listOf(
            "dev.trmx.gui",
            "com.termux",
            "com.termux.api",
            "com.termux.gui",
            "com.termux.window",
            "com.termux.styling",
            "android",
            "com.android.systemui",
            "com.google.android.systemui",
            "com.android.settings",
            "com.google.android.settings",
            "com.samsung.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.providers.telephony",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.keychain",
            "com.android.providers.settings",
            "com.android.shell",
            "com.google.android.ext.services",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.samsung.android.incallui",
            "com.samsung.android.settings",
            "com.custom.rom.systemui",
        )

        for (pkg in critical) {
            assertTrue("Expected $pkg to be marked as CORE", AppCategories.isCore(pkg))
            assertFalse("Expected $pkg to NOT be killable", AppCategories.isKillable(pkg))
            assertEquals("Expected $pkg category to be CORE", AppCategory.CORE, AppCategories.categoryFor(pkg))
        }
    }

    @Test
    fun testNonCoreAppsAreKillable() {
        val nonCore = listOf(
            "com.sec.android.app.samsungapps",
            "com.samsung.android.themestore",
            "com.samsung.android.game.gamehome",
            "com.sec.android.app.voicenote",
            "com.samsung.android.dialer",
            "com.google.android.apps.recorder",
            "com.google.android.dialer",
        )

        for (pkg in nonCore) {
            assertFalse("Non-core app $pkg must not be CORE", AppCategories.isCore(pkg))
            assertTrue("Non-core app $pkg must be killable", AppCategories.isKillable(pkg))
            assertEquals("Expected category UNKNOWN for $pkg", AppCategory.UNKNOWN, AppCategories.categoryFor(pkg))
        }
    }

    @Test
    fun testDialerUiVsPhoneServiceDistinction() {
        // Critical safety distinction from design plan:
        // Phone service (com.android.phone) is CORE and must NEVER be stopped.
        // Dialer UI (com.samsung.android.dialer / com.google.android.dialer) is killable UI.
        assertTrue(AppCategories.isCore("com.android.phone"))
        assertFalse(AppCategories.isKillable("com.android.phone"))

        assertTrue(AppCategories.isKillable("com.samsung.android.dialer"))
        assertEquals(AppCategory.UNKNOWN, AppCategories.categoryFor("com.samsung.android.dialer"))

        assertTrue(AppCategories.isKillable("com.google.android.dialer"))
        assertEquals(AppCategory.UNKNOWN, AppCategories.categoryFor("com.google.android.dialer"))
    }

    @Test
    fun testUnknownPackagesDefaultToUnknown() {
        val unknownPkg = "com.thirdparty.randomgame"
        assertFalse(AppCategories.isCore(unknownPkg))
        assertTrue(AppCategories.isKillable(unknownPkg))
        assertEquals(AppCategory.UNKNOWN, AppCategories.categoryFor(unknownPkg))
    }

    @Test
    fun testDynamicCorePatterns() {
        val dynamicCore = listOf(
            "com.oem.custom.systemui",
            "com.android.systemui",
            "com.google.android.settings",
            "com.samsung.android.settings",
            "com.android.settings",
            "com.oem.telecom",
            "com.carrier.telephony",
            "com.samsung.android.incallui",
            "com.termux.customplugin",
        )
        for (pkg in dynamicCore) {
            assertTrue("Expected dynamic pattern match for $pkg", AppCategories.isCore(pkg))
            assertFalse("Expected $pkg to not be killable", AppCategories.isKillable(pkg))
            assertEquals(AppCategory.CORE, AppCategories.categoryFor(pkg))
        }
    }

    @Test
    fun testBlankOrEmptyPackagesAreProtected() {
        assertTrue(AppCategories.isCore(""))
        assertTrue(AppCategories.isCore("   "))
        assertFalse(AppCategories.isKillable(""))
    }

    @Test
    fun testIsKillableRunningRules() {
        // 1. User-installed apps (isSystem = false)
        val userApp = "com.spotify.music"
        assertTrue(
            "Running user-installed app must be killable running",
            AppCategories.isKillableRunning(packageName = userApp, isSystem = false, isRunning = true),
        )
        assertFalse(
            "Non-running user-installed app must NOT be in running tab",
            AppCategories.isKillableRunning(packageName = userApp, isSystem = false, isRunning = false),
        )

        // 2. Vendor apps (isSystem = true)
        val vendorApp = "com.sec.android.app.popupcalculator"
        assertTrue(
            "Running vendor app must be killable running",
            AppCategories.isKillableRunning(packageName = vendorApp, isSystem = true, isRunning = true),
        )
        assertFalse(
            "Non-running vendor app must NOT be in running tab",
            AppCategories.isKillableRunning(packageName = vendorApp, isSystem = true, isRunning = false),
        )

        // 3. System apps NOT in old suggestion list
        // v5 Rule 1: active AND NOT core. Hardcoded suggestion list removed.
        val unlistedSystem = "com.samsung.android.knox.containercore"
        assertTrue(
            "Running unlisted system app must be in running tab",
            AppCategories.isKillableRunning(packageName = unlistedSystem, isSystem = true, isRunning = true),
        )
        assertFalse(
            "Non-running unlisted system app must NOT be in running tab",
            AppCategories.isKillableRunning(packageName = unlistedSystem, isSystem = true, isRunning = false),
        )

        // 4. Core system apps can NEVER be in running tab, even if running
        val coreList = listOf(
            "dev.trmx.gui",
            "com.termux",
            "com.termux.api",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.gms",
            "com.android.phone",
            "com.sec.android.app.launcher",
            "com.custom.rom.systemui",
        )
        for (corePkg in coreList) {
            assertFalse(
                "Core package $corePkg can NEVER be in running tab even if running",
                AppCategories.isKillableRunning(packageName = corePkg, isSystem = true, isRunning = true),
            )
            assertFalse(
                "Core package $corePkg can NEVER be in running tab when not running",
                AppCategories.isKillableRunning(packageName = corePkg, isSystem = true, isRunning = false),
            )
        }
    }

    @Test
    fun testRunningTabExcludesAllCoreBlocklistPackages() {
        // Enforce code guarantee: every single package in CORE_PACKAGES must return false for isKillableRunning
        for (pkg in AppCategories.CORE_PACKAGES) {
            assertFalse(
                "Package $pkg in CORE_PACKAGES must never be in running tab",
                AppCategories.isKillableRunning(packageName = pkg, isSystem = true, isRunning = true),
            )
            assertFalse(
                "Package $pkg in CORE_PACKAGES must never be in running tab even if flagged user-installed",
                AppCategories.isKillableRunning(packageName = pkg, isSystem = false, isRunning = true),
            )
        }
    }

    @Test
    fun testOtherTabCanNeverContainInstalledThirdPartyPackages() {
        // v3 Spec Rule 2 & 3: Other must contain ZERO installed third-party packages, running or not.
        val installedApps = listOf(
            "in.gov.uidai.aadhaar",
            "com.spotify.music",
            "com.whatsapp",
            "org.videolan.vlc",
            "com.supercell.clashofclans",
            "com.thirdparty.anyapp",
        )

        for (pkg in installedApps) {
            // Test with running = true
            assertFalse(
                "Installed third-party app $pkg (running) must NEVER belong to Other tab",
                AppCategories.belongsToOtherTab(packageName = pkg, isSystem = false, isRunning = true),
            )
            // Test with running = false
            assertFalse(
                "Installed third-party app $pkg (not running) must NEVER belong to Other tab",
                AppCategories.belongsToOtherTab(packageName = pkg, isSystem = false, isRunning = false),
            )

            // Also test with AppInfo object overload
            val runningAppInfo = AppInfo(
                packageName = pkg,
                label = pkg,
                category = AppCategory.UNKNOWN,
                isSystem = false,
                isRunning = true,
            )
            assertFalse(
                "AppInfo for $pkg (running) must NEVER belong to Other tab",
                AppCategories.belongsToOtherTab(runningAppInfo),
            )

            val stoppedAppInfo = AppInfo(
                packageName = pkg,
                label = pkg,
                category = AppCategory.UNKNOWN,
                isSystem = false,
                isRunning = false,
            )
            assertFalse(
                "AppInfo for $pkg (not running) must NEVER belong to Other tab",
                AppCategories.belongsToOtherTab(stoppedAppInfo),
            )
        }
    }

    @Test
    fun testNonRunningInstalledAppAppearsInNoTab() {
        // v3 Spec Rule 2: If user-installed and NOT running -> appears NOWHERE (neither Running nor Other).
        val userApps = listOf(
            "in.gov.uidai.aadhaar",
            "com.spotify.music",
            "com.whatsapp",
        )

        for (pkg in userApps) {
            val inRunning = AppCategories.isKillableRunning(packageName = pkg, isSystem = false, isRunning = false)
            val inOther = AppCategories.belongsToOtherTab(packageName = pkg, isSystem = false, isRunning = false)
            val isCore = AppCategories.isCore(pkg)

            assertFalse("Non-running installed app $pkg must NOT be in Running tab", inRunning)
            assertFalse("Non-running installed app $pkg must NOT be in Other tab", inOther)
            assertFalse("Installed app $pkg must NOT be in Core tab", isCore)
            // Mathematically verified: matches zero tabs.
        }
    }

    @Test
    fun testRunningInstalledAppAppearsOnlyInRunningTab() {
        // v3 Spec Rule 2 & 4: If user-installed and RUNNING -> Running tab ONLY.
        val userApps = listOf(
            "in.gov.uidai.aadhaar",
            "com.spotify.music",
            "com.whatsapp",
        )

        for (pkg in userApps) {
            val inRunning = AppCategories.isKillableRunning(packageName = pkg, isSystem = false, isRunning = true)
            val inOther = AppCategories.belongsToOtherTab(packageName = pkg, isSystem = false, isRunning = true)
            val isCore = AppCategories.isCore(pkg)

            assertTrue("Running installed app $pkg must be in Running tab", inRunning)
            assertFalse("Running installed app $pkg must NOT be in Other tab", inOther)
            assertFalse("Installed app $pkg must NOT be in Core tab", isCore)
        }
    }

    @Test
    fun testOtherTabContainsOnlyNonCoreSystemApps() {
        // v5 Spec Rule 2: Other tab content = non-core SYSTEM apps that are NOT in Running (not active).

        // 1. Non-running vendor app -> belongs to Other
        val vendorApp = "com.sec.android.app.popupcalculator"
        assertTrue(
            "Non-running vendor app must belong to Other tab",
            AppCategories.belongsToOtherTab(packageName = vendorApp, isSystem = true, isRunning = false),
        )
        // When running, it belongs to Running tab, NOT Other
        assertFalse(
            "Running vendor app belongs to Running, NOT Other",
            AppCategories.belongsToOtherTab(packageName = vendorApp, isSystem = true, isRunning = true),
        )

        // 2. System app NOT in old suggestion list -> belongs to Other when inactive, Running when active
        val unlistedSystem = "com.samsung.android.knox.containercore"
        assertTrue(
            "Non-running unlisted system app must belong to Other tab",
            AppCategories.belongsToOtherTab(packageName = unlistedSystem, isSystem = true, isRunning = false),
        )
        assertFalse(
            "Running unlisted system app belongs to Running, NOT Other",
            AppCategories.belongsToOtherTab(packageName = unlistedSystem, isSystem = true, isRunning = true),
        )

        // 3. Core system apps -> can NEVER belong to Other tab
        for (pkg in AppCategories.CORE_PACKAGES) {
            assertFalse(
                "Core package $pkg must NEVER belong to Other tab (running)",
                AppCategories.belongsToOtherTab(packageName = pkg, isSystem = true, isRunning = true),
            )
            assertFalse(
                "Core package $pkg must NEVER belong to Other tab (not running)",
                AppCategories.belongsToOtherTab(packageName = pkg, isSystem = true, isRunning = false),
            )
        }
    }

    @Test
    fun testUsageStatsWindowIs24Hours() {
        // v4 Spec: Window changed from 30 minutes to 24 hours so every app used today appears in Running
        assertEquals(24 * 60 * 60 * 1000L, AppKillerEngine.USAGE_STATS_WINDOW_MS)
    }

    @Test
    fun testV5EligibilityRules() {
        // Verification 1: Active non-core SYSTEM app NOT in old suggestion list now belongs to Running
        val activeUnlistedSystem = "com.samsung.android.knox.containercore"
        assertFalse(AppCategories.isCore(activeUnlistedSystem))
        assertTrue(
            "Active non-core SYSTEM app NOT in old suggestion list belongs to Running",
            AppCategories.isKillableRunning(packageName = activeUnlistedSystem, isSystem = true, isRunning = true),
        )
        assertFalse(
            "Active non-core SYSTEM app must NOT belong to Other",
            AppCategories.belongsToOtherTab(packageName = activeUnlistedSystem, isSystem = true, isRunning = true),
        )

        // Verification 2: Non-active installed app belongs to NO tab
        val nonActiveInstalled = "com.spotify.music"
        assertFalse(
            "Non-active installed app must NOT belong to Running",
            AppCategories.isKillableRunning(packageName = nonActiveInstalled, isSystem = false, isRunning = false),
        )
        assertFalse(
            "Non-active installed app must NOT belong to Other",
            AppCategories.belongsToOtherTab(packageName = nonActiveInstalled, isSystem = false, isRunning = false),
        )
        assertFalse(
            "Non-active installed app must NOT belong to Core",
            AppCategories.isCore(nonActiveInstalled),
        )

        // Verification 3: Core app can NEVER be selected or in Running/Other
        val coreApp = "com.android.systemui"
        assertTrue(AppCategories.isCore(coreApp))
        assertFalse(AppCategories.isKillable(coreApp))
        assertFalse(
            "Core app can never be in Running even if active",
            AppCategories.isKillableRunning(packageName = coreApp, isSystem = true, isRunning = true),
        )
        assertFalse(
            "Core app can never belong to Other",
            AppCategories.belongsToOtherTab(packageName = coreApp, isSystem = true, isRunning = false),
        )
    }
}
