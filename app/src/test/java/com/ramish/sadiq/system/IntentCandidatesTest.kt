package com.ramish.sadiq.system

import com.ramish.sadiq.contract.SettingsTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The deep-link table of §4.7, held still.
 *
 * WP-22 walks the same table on the Poco F7 and corrects the OEM copy through a WP-17 fix; this test
 * is the part that must not depend on the device: every target has somewhere to go, every chain has
 * an app-info page at the end, and the first candidate is the page the plan actually names.
 */
class IntentCandidatesTest {

    @Test
    fun everyTargetHasAtLeastTwoCandidates() {
        SettingsTarget.entries.forEach { target ->
            val candidates = IntentCandidates.candidates(target)
            assertTrue(candidates.size >= 2, "$target has no fallback: ${candidates.map { it.action }}")
        }
    }

    @Test
    fun everyChainEndsOnAnAppInfoPage() {
        SettingsTarget.entries.forEach { target ->
            val last = IntentCandidates.candidates(target).last()
            assertTrue(
                IntentCandidates.isAppInfo(last),
                "$target dead-ends on ${last.action ?: last.componentClass}",
            )
        }
    }

    @Test
    fun appInfoItselfStartsWithTheSystemPage() {
        val first = IntentCandidates.candidates(SettingsTarget.APP_INFO).first()
        assertEquals(IntentCandidates.ACTION_APPLICATION_DETAILS_SETTINGS, first.action)
        assertEquals(IntentData.PACKAGE_URI, first.data)
    }

    @Test
    fun accessibilityStartsWithTheServicePageAndCarriesOurComponent() {
        val first = IntentCandidates.candidates(SettingsTarget.ACCESSIBILITY).first()
        assertEquals(IntentCandidates.ACTION_ACCESSIBILITY_DETAILS_SETTINGS, first.action)
        assertEquals(
            IntentCandidates.ACTION_ACCESSIBILITY_DETAILS_SETTINGS,
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS",
        )
        assertEquals(
            mapOf(IntentCandidates.EXTRA_COMPONENT_NAME to IntentExtra.SelfAccessibilityComponent),
            first.extras,
        )
    }

    @Test
    fun theAccessibilityFallbackIsTheFullList() {
        val actions = IntentCandidates.candidates(SettingsTarget.ACCESSIBILITY_DETAILS).map { it.action }
        assertEquals(
            listOf(
                "android.settings.ACCESSIBILITY_DETAILS_SETTINGS",
                "android.settings.ACCESSIBILITY_SETTINGS",
                "android.settings.APPLICATION_DETAILS_SETTINGS",
            ),
            actions,
        )
    }

    @Test
    fun thePopUpStepTriesTheMiuiPermissionEditorFirst() {
        val first = IntentCandidates.candidates(SettingsTarget.MIUI_OTHER_PERMISSIONS).first()
        assertEquals(IntentCandidates.ACTION_MIUI_APP_PERM_EDITOR, first.action)
        assertEquals(IntentCandidates.MIUI_SECURITY_CENTER, first.componentPackage)
        assertEquals(IntentCandidates.MIUI_PERMISSIONS_EDITOR, first.componentClass)
        assertEquals(IntentExtra.PackageName, first.extras[IntentCandidates.EXTRA_MIUI_PKG_NAME])

        val second = IntentCandidates.candidates(SettingsTarget.MIUI_OTHER_PERMISSIONS)[1]
        assertEquals(IntentCandidates.MIUI_PERMISSIONS_EDITOR_ALT, second.componentClass)
    }

    @Test
    fun theAutostartStepTriesTheAutostartActivityThenSecurityCentre() {
        val candidates = IntentCandidates.candidates(SettingsTarget.MIUI_AUTOSTART)
        assertEquals(IntentCandidates.MIUI_AUTOSTART, candidates[0].componentClass)
        assertEquals(IntentCandidates.MIUI_SECURITY_MAIN, candidates[1].componentClass)
        assertEquals(IntentCandidates.MIUI_SECURITY_CENTER, candidates[1].componentPackage)
    }

    @Test
    fun theBatterySaverStepCarriesTheNameAndLabelPowerKeeperReads() {
        val first = IntentCandidates.candidates(SettingsTarget.MIUI_BATTERY_SAVER).first()
        assertEquals(IntentCandidates.MIUI_POWER_KEEPER, first.componentPackage)
        assertEquals(IntentCandidates.MIUI_HIDDEN_APPS_CONFIG, first.componentClass)
        assertEquals(IntentExtra.PackageName, first.extras[IntentCandidates.EXTRA_POWERKEEPER_PACKAGE])
        assertEquals(IntentExtra.PackageLabel, first.extras[IntentCandidates.EXTRA_POWERKEEPER_LABEL])
    }

    @Test
    fun theBatteryOptimisationStepAsksForTheExemptionWithThePackageUri() {
        val first = IntentCandidates.candidates(SettingsTarget.BATTERY_OPT_REQUEST).first()
        assertEquals("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", first.action)
        assertEquals(IntentData.PACKAGE_URI, first.data)
        assertEquals("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", IntentCandidates.candidates(
            SettingsTarget.BATTERY_OPT_REQUEST,
        )[1].action)
    }

    @Test
    fun theUsageStepTriesThePackageUriThenTheList() {
        val candidates = IntentCandidates.candidates(SettingsTarget.USAGE_ACCESS)
        assertEquals(IntentData.PACKAGE_URI, candidates[0].data)
        assertEquals(IntentData.NONE, candidates[1].data)
        assertEquals(candidates[0].action, candidates[1].action)
        assertEquals("android.settings.USAGE_ACCESS_SETTINGS", candidates[0].action)
    }

    @Test
    fun theNotificationStepCarriesThePackageNameInBothShapes() {
        val candidates = IntentCandidates.candidates(SettingsTarget.NOTIFICATIONS)
        assertEquals(IntentExtra.PackageName, candidates[0].extras[IntentCandidates.EXTRA_APP_PACKAGE])
        assertEquals(IntentExtra.PackageName, candidates[1].extras[IntentCandidates.EXTRA_LEGACY_APP_PACKAGE])
        assertEquals(IntentExtra.PackageUid, candidates[1].extras[IntentCandidates.EXTRA_LEGACY_APP_UID])
        assertEquals("android.settings.APP_NOTIFICATION_SETTINGS", candidates[0].action)
    }

    @Test
    fun theChannelStepNamesTheKeepAliveChannel() {
        val first = IntentCandidates.candidates(SettingsTarget.CHANNEL_GUARD_STATUS).first()
        assertEquals("android.settings.CHANNEL_NOTIFICATION_SETTINGS", first.action)
        assertEquals(IntentExtra.PackageName, first.extras[IntentCandidates.EXTRA_APP_PACKAGE])
        assertEquals(IntentExtra.Literal("guard_status"), first.extras[IntentCandidates.EXTRA_CHANNEL_ID])
    }

    @Test
    fun theLiveUpdateStepTriesThePromotionPageThenTheNotificationPage() {
        val candidates = IntentCandidates.candidates(SettingsTarget.LIVE_UPDATES)
        assertEquals("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS", candidates[0].action)
        assertEquals(IntentExtra.PackageName, candidates[0].extras[IntentCandidates.EXTRA_APP_PACKAGE])
        assertEquals("android.settings.APP_NOTIFICATION_SETTINGS", candidates[1].action)
        assertEquals(IntentExtra.PackageName, candidates[1].extras[IntentCandidates.EXTRA_APP_PACKAGE])
    }

    @Test
    fun noCandidateNamesAClassWithoutAPackage() {
        SettingsTarget.entries.forEach { target ->
            IntentCandidates.candidates(target).forEach { candidate ->
                if (candidate.componentClass != null) {
                    assertNotNull(candidate.componentPackage, "$target has a class with no package")
                }
            }
        }
    }

    @Test
    fun theExtrasUseTheExactPlatformNames() {
        // These strings are the contract with the Settings and Security Center activities; a rename
        // would silently drop the extra and open the wrong page.
        assertEquals("android.intent.extra.COMPONENT_NAME", IntentCandidates.EXTRA_COMPONENT_NAME)
        assertEquals("android.provider.extra.APP_PACKAGE", IntentCandidates.EXTRA_APP_PACKAGE)
        assertEquals("android.provider.extra.CHANNEL_ID", IntentCandidates.EXTRA_CHANNEL_ID)
        assertEquals("extra_pkgname", IntentCandidates.EXTRA_MIUI_PKG_NAME)
        assertEquals("package_name", IntentCandidates.EXTRA_POWERKEEPER_PACKAGE)
        assertEquals("package_label", IntentCandidates.EXTRA_POWERKEEPER_LABEL)
        assertEquals("app_package", IntentCandidates.EXTRA_LEGACY_APP_PACKAGE)
        assertEquals("app_uid", IntentCandidates.EXTRA_LEGACY_APP_UID)
    }

    @Test
    fun theAccessibilityServiceClassIsTheManifestComponent() {
        assertEquals(
            "com.ramish.sadiq.guard.service.GuardAccessibilityService",
            IntentCandidates.ACCESSIBILITY_SERVICE_CLASS,
        )
    }

    @Test
    fun noChainIsEmptyAndNoneRepeatsAWholeCandidate() {
        SettingsTarget.entries.forEach { target ->
            val candidates = IntentCandidates.candidates(target)
            assertFalse(candidates.isEmpty(), "$target is empty")
            assertEquals(
                candidates.size,
                candidates.distinct().size,
                "$target repeats a candidate, which wastes a tap",
            )
        }
    }
}
