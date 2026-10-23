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
    fun testSafePackagesAreSuggestedAndKillable() {
        val safe = listOf(
            "com.sec.android.app.samsungapps",
            "com.samsung.android.themestore",
            "com.samsung.android.game.gamehome",
            "com.sec.android.app.voicenote",
            "com.samsung.android.dialer",
            "com.google.android.apps.recorder",
            "com.google.android.dialer",
        )

        for (pkg in safe) {
            assertFalse("Safe app $pkg must not be CORE", AppCategories.isCore(pkg))
            assertTrue("Safe app $pkg must be killable", AppCategories.isKillable(pkg))
            assertEquals("Expected category SAFE for $pkg", AppCategory.SAFE, AppCategories.categoryFor(pkg))
        }
    }

    @Test
    fun testDialerUiVsPhoneServiceDistinction() {
        // Critical safety distinction from design plan:
        // Phone service (com.android.phone) is CORE and must NEVER be stopped.
        // Dialer UI (com.samsung.android.dialer / com.google.android.dialer) is SAFE UI.
        assertTrue(AppCategories.isCore("com.android.phone"))
        assertFalse(AppCategories.isKillable("com.android.phone"))

        assertTrue(AppCategories.isKillable("com.samsung.android.dialer"))
        assertEquals(AppCategory.SAFE, AppCategories.categoryFor("com.samsung.android.dialer"))

        assertTrue(AppCategories.isKillable("com.google.android.dialer"))
        assertEquals(AppCategory.SAFE, AppCategories.categoryFor("com.google.android.dialer"))
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

        // 2. Suggested vendor apps (isSystem = true, in suggestion list)
        val suggestedVendor = "com.sec.android.app.popupcalculator"
        assertTrue(
            "Running suggested vendor app must be killable running",
            AppCategories.isKillableRunning(packageName = suggestedVendor, isSystem = true, isRunning = true),
        )
        assertFalse(
            "Non-running suggested vendor app must NOT be in running tab",
            AppCategories.isKillableRunning(packageName = suggestedVendor, isSystem = true, isRunning = false),
        )

        // 3. Unknown system apps (isSystem = true, not in suggestion list)
        val unknownSystem = "com.samsung.android.knox.containercore"
        assertFalse(
            "Running unknown system app must NOT be in running tab (must go to Other)",
            AppCategories.isKillableRunning(packageName = unknownSystem, isSystem = true, isRunning = true),
        )
        assertFalse(
            "Non-running unknown system app must NOT be in running tab",
            AppCategories.isKillableRunning(packageName = unknownSystem, isSystem = true, isRunning = false),
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
        // v3 Spec Rule 3: Other tab content = non-core SYSTEM apps only:
        // non-running system apps, plus running system apps that are NOT in the safe suggestion list.

        // 1. Non-running suggested vendor app -> belongs to Other
        val suggestedVendor = "com.sec.android.app.popupcalculator"
        assertTrue(
            "Non-running suggested vendor app must belong to Other tab",
            AppCategories.belongsToOtherTab(packageName = suggestedVendor, isSystem = true, isRunning = false),
        )
        // When running, it belongs to Running tab, NOT Other
        assertFalse(
            "Running suggested vendor app belongs to Running, NOT Other",
            AppCategories.belongsToOtherTab(packageName = suggestedVendor, isSystem = true, isRunning = true),
        )

        // 2. Unknown system app (not in suggestion list) -> belongs to Other whether running or not
        val unknownSystem = "com.samsung.android.knox.containercore"
        assertTrue(
            "Non-running unknown system app must belong to Other tab",
            AppCategories.belongsToOtherTab(packageName = unknownSystem, isSystem = true, isRunning = false),
        )
        assertTrue(
            "Running unknown system app (not suggested) must belong to Other tab",
            AppCategories.belongsToOtherTab(packageName = unknownSystem, isSystem = true, isRunning = true),
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
}
