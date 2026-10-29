package com.ramish.sadiq.system

import android.app.AppOpsManager
import com.ramish.sadiq.contract.Tri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ROM detection and the app-op mapping of §4.7.
 *
 * The two version strings are the ones the plan names for the Poco F7, so this test is the off-device
 * half of WP-22's "every menu name is right" check: if these two parse, the wizard knows whether it
 * is showing HyperOS 2 or HyperOS 3 copy.
 */
class HyperOsTest {

    private val xiaomi = "Xiaomi"

    @Test
    fun hyperOsTwoParsesFromThePocoBuildString() {
        assertEquals(
            2,
            HyperOs.major(xiaomi, "POCO", "OS2.0.104.0.VOJINXM"),
        )
    }

    @Test
    fun hyperOsThreeParsesFromThePocoBuildString() {
        assertEquals(
            3,
            HyperOs.major(xiaomi, "POCO", "OS3.0.5.0.WOJINXM"),
        )
    }

    @Test
    fun aNonXiaomiDeviceHasNoHyperOsMajorEvenWithAVersionShapedBuildString() {
        assertNull(HyperOs.major("Google", "google", "OS2.0.104.0.VOJINXM"))
        assertNull(HyperOs.major("samsung", "samsung", "OS3.0.5.0.WOJINXM"))
    }

    @Test
    fun aMiuiBuildHasNoHyperOsMajor() {
        // MIUI's own incremental version: still Xiaomi, but there is no HyperOS generation to show.
        assertNull(HyperOs.major(xiaomi, "Redmi", "V14.0.6.0.TKHMIXM"))
        assertFalse(HyperOs.looksLikeHyperOs("V14.0.6.0.TKHMIXM"))
    }

    @Test
    fun xiaomiFamilyRecognisesXiaomiRedmiAndPoco() {
        assertTrue(HyperOs.isXiaomiFamily("Xiaomi", "POCO"))
        assertTrue(HyperOs.isXiaomiFamily("Xiaomi", "Redmi"))
        assertTrue(HyperOs.isXiaomiFamily("Xiaomi", "Xiaomi"))
        // Some regional builds report the brand only.
        assertTrue(HyperOs.isXiaomiFamily(null, "POCO"))
        assertTrue(HyperOs.isXiaomiFamily("Redmi", null))
    }

    @Test
    fun xiaomiFamilyRejectsEveryOtherManufacturer() {
        assertFalse(HyperOs.isXiaomiFamily("Google", "google"))
        assertFalse(HyperOs.isXiaomiFamily("OnePlus", "OnePlus"))
        assertFalse(HyperOs.isXiaomiFamily(null, null))
        assertFalse(HyperOs.isXiaomiFamily("", ""))
    }

    @Test
    fun caseAndSurroundingWhitespaceDoNotMatter() {
        assertTrue(HyperOs.isXiaomiFamily(" xIaOmI ", " poco "))
        assertEquals(3, HyperOs.major(" XIAOMI ", "poco", "  OS3.0.5.0.WOJINXM  "))
    }

    @Test
    fun aVersionStringThatOnlyMentionsOsIsNotHyperOs() {
        assertFalse(HyperOs.looksLikeHyperOs("MIUI-OS2.0.1"))
        assertFalse(HyperOs.looksLikeHyperOs("OS"))
        assertFalse(HyperOs.looksLikeHyperOs(""))
        assertNull(HyperOs.major(xiaomi, "POCO", "OS"))
    }

    @Test
    fun blankAndGarbageIncrementalsAreNull() {
        assertNull(HyperOs.major(xiaomi, "POCO", null))
        assertNull(HyperOs.major(xiaomi, "POCO", ""))
        assertNull(HyperOs.major(xiaomi, "POCO", "unknown"))
        assertNull(HyperOs.versionName(null))
    }

    @Test
    fun versionNameIsTheBuildStringWithoutThePadding() {
        assertEquals("OS3.0.5.0.WOJINXM", HyperOs.versionName("  OS3.0.5.0.WOJINXM "))
        assertNull(HyperOs.versionName("V14.0.6.0.TKHMIXM"))
    }

    @Test
    fun theAppOpModesMapToYesNoAndUnknown() {
        assertEquals(Tri.YES, MiuiAppOps.triForMode(AppOpsManager.MODE_ALLOWED))
        assertEquals(Tri.NO, MiuiAppOps.triForMode(AppOpsManager.MODE_IGNORED))
        assertEquals(Tri.NO, MiuiAppOps.triForMode(AppOpsManager.MODE_ERRORED))
        // "Default" means the user never decided; that is not a no.
        assertEquals(Tri.UNKNOWN, MiuiAppOps.triForMode(AppOpsManager.MODE_DEFAULT))
    }

    @Test
    fun foregroundOnlyIsUnknownRatherThanNo() {
        // MODE_FOREGROUND allows the op only while the app is in front, which says nothing about the
        // background launch "display pop-up windows" exists for.
        assertEquals(Tri.UNKNOWN, MiuiAppOps.triForMode(AppOpsManager.MODE_FOREGROUND))
    }

    @Test
    fun anUnrecognisedModeIsUnknown() {
        assertEquals(Tri.UNKNOWN, MiuiAppOps.triForMode(99))
    }

    @Test
    fun theOemOpNumbersAreTheOnesThePlanNames() {
        assertEquals(10021, MiuiAppOps.OP_BACKGROUND_POPUPS)
        assertEquals(10008, MiuiAppOps.OP_AUTOSTART)
    }
}
