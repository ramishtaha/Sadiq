package com.ramish.sadiq.core.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three lists in §4.7 O2, §3.6 and WP-23, held still.
 *
 * These package names are load-bearing: the onboarding wizard pre-selects the feeds, the web guard
 * reads only these browsers' address bars, and the foreground resolver refuses to interrupt anything
 * in the never-guard set. A typo here silently disables a feature rather than failing a build, which
 * is exactly why the lists are asserted rather than trusted.
 */
class AppCatalogTest {

    @Test
    fun knownFeedsAreExactlyTheElevenThePlanNames() {
        assertEquals(
            listOf(
                "com.instagram.android",
                "com.google.android.youtube",
                "com.facebook.katana",
                "com.twitter.android",
                "com.reddit.frontpage",
                "com.snapchat.android",
                "in.mohalla.sharechat",
                "in.mohalla.video",
                "com.eterno.shortvideos",
                "com.mxtakatak",
                "com.instagram.barcelona",
            ),
            AppCatalog.knownFeeds,
        )
        assertEquals(
            AppCatalog.knownFeeds.size,
            AppCatalog.knownFeeds.distinct().size,
            "a duplicate feed would make the picker order depend on which copy won",
        )
    }

    @Test
    fun browsersAreTheFiveTheWebGuardCanRead() {
        assertEquals(
            listOf(
                "com.android.chrome",
                "com.brave.browser",
                "com.microsoft.emmx",
                "org.mozilla.firefox",
                "com.mi.globalbrowser",
            ),
            AppCatalog.browsers,
        )
        assertEquals(AppCatalog.browsers.size, AppCatalog.browsers.distinct().size)
    }

    @Test
    fun noSuggestedFeedIsAlsoNeverGuarded() {
        AppCatalog.knownFeeds.forEach { feed ->
            assertFalse(feed in AppCatalog.baseNeverGuard, "$feed cannot be both suggested and transparent")
            assertFalse(AppCatalog.isBrowser(feed), "$feed is not a browser")
        }
    }

    @Test
    fun neverGuardCoversEveryCategoryOfSection36() {
        val set = AppCatalog.neverGuard(
            ownPackage = "com.ramish.sadiq",
            defaultHomePackage = null,
            enabledImePackages = emptySet(),
        )
        listOf(
            "com.android.dialer",
            "com.android.contacts",
            "com.android.mms",
            "com.android.camera",
            "com.android.deskclock",
            "com.android.settings",
            "com.android.systemui",
            "com.miui.home",
            "com.miui.securitycenter",
            "com.rafiq.app",
        ).forEach { packageName ->
            assertTrue(packageName in set, "$packageName must never be guarded")
        }
    }

    @Test
    fun theOwnPackageHomeAndKeyboardsAreAddedAtRuntime() {
        val set = AppCatalog.neverGuard(
            ownPackage = "com.ramish.sadiq",
            defaultHomePackage = "com.example.launcher",
            enabledImePackages = setOf("com.example.ime", "com.example.ime2"),
        )
        assertTrue("com.ramish.sadiq" in set)
        assertTrue("com.example.launcher" in set)
        assertTrue("com.example.ime" in set)
        assertTrue("com.example.ime2" in set)
    }

    @Test
    fun aMissingLaunchOrKeyboardAddsNothingAndBreaksNothing() {
        val set = AppCatalog.neverGuard(
            ownPackage = "com.ramish.sadiq",
            defaultHomePackage = null,
            enabledImePackages = emptySet(),
        )
        assertTrue(set.containsAll(AppCatalog.baseNeverGuard))
        assertFalse(set.any { packageName -> packageName.isBlank() }, "a blank entry would match nothing and hide a bug")
    }

    @Test
    fun blankRuntimePackagesAreIgnored() {
        val set = AppCatalog.neverGuard(
            ownPackage = "com.ramish.sadiq",
            defaultHomePackage = "   ",
            enabledImePackages = setOf("", "com.example.ime"),
        )
        assertFalse("" in set)
        assertFalse("   " in set)
        assertTrue("com.example.ime" in set)
    }

    @Test
    fun theNeverGuardSetIsAUnionAndNotAReplacement() {
        val runtime = AppCatalog.neverGuard("com.ramish.sadiq", "com.example.home", setOf("com.example.ime"))
        assertEquals(AppCatalog.baseNeverGuard.size + 3, runtime.size)
    }

    @Test
    fun feedRankFollowsTheTableAndUnknownAppsSortLast() {
        assertEquals(0, AppCatalog.feedRank("com.instagram.android"))
        assertEquals(10, AppCatalog.feedRank("com.instagram.barcelona"))
        assertEquals(Int.MAX_VALUE, AppCatalog.feedRank("com.example.notafeed"))
        assertTrue(AppCatalog.isKnownFeed("com.google.android.youtube"))
        assertFalse(AppCatalog.isKnownFeed("com.example.notafeed"))
        assertTrue(AppCatalog.isBrowser("com.android.chrome"))
        assertFalse(AppCatalog.isBrowser("com.example.notabrowser"))
    }

    @Test
    fun knownFeedsFirstPutsTheFeedsInTableOrderAndKeepsTheRestStable() {
        val apps = listOf(
            Row("com.example.zeta", "Zeta"),
            Row("com.reddit.frontpage", "Reddit"),
            Row("com.example.alpha", "Alpha"),
            Row("com.instagram.android", "Instagram"),
            Row("com.twitter.android", "X"),
        )
        assertEquals(
            listOf(
                "com.instagram.android",
                // X is third in the table, Reddit fourth: the picker follows the plan's order.
                "com.twitter.android",
                "com.reddit.frontpage",
                "com.example.zeta",
                "com.example.alpha",
            ),
            AppCatalog.knownFeedsFirst(apps) { row -> row.packageName }.map { it.packageName },
        )
    }

    @Test
    fun knownFeedsFirstDoesNotChangeTheInputList() {
        val apps = listOf(Row("com.example.a", "A"), Row("com.instagram.android", "Instagram"))
        val ordered = AppCatalog.knownFeedsFirst(apps) { row -> row.packageName }
        assertEquals(listOf("com.example.a", "com.instagram.android"), apps.map { it.packageName })
        assertEquals(listOf("com.instagram.android", "com.example.a"), ordered.map { it.packageName })
    }

    /** A picker row, reduced to what the sort needs. */
    private data class Row(val packageName: String, val label: String)
}
