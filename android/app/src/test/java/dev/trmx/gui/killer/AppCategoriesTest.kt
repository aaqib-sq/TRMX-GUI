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
}
