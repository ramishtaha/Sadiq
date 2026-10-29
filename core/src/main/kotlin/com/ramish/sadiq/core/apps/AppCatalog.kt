package com.ramish.sadiq.core.apps

/**
 * The app lists Sadiq is built around: the feeds worth guarding, the browsers that can be guarded by
 * domain, and the apps that must never be guarded at all.
 *
 * The three lists live in `core` rather than next to the package manager because they are product
 * decisions, not platform facts, and because four features need to agree on them: the app picker
 * (which feeds to suggest first), the web guard (which browsers can be inspected), the foreground
 * resolver (which packages are transparent), and the onboarding wizard (which installed apps to
 * pre-select).
 *
 * Nothing here reads the device. The runtime half of [neverGuard] — the launcher the user actually
 * has and the keyboards he actually enabled — is resolved in the app layer and passed in, so this
 * object stays a pure function of its arguments and can be held still by a test.
 */
object AppCatalog {

    /**
     * The known short-video and feed apps, in the order §4.7 O2 lists them.
     *
     * The order is user-visible: the picker shows these first, in this sequence, and the onboarding
     * wizard pre-selects them. It is Instagram, YouTube, Facebook, X, Reddit, Snapchat, ShareChat,
     * Moj, Josh, MX TakaTak, Threads.
     */
    val knownFeeds: List<String> = listOf(
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
    )

    /**
     * The browsers the web guard can read an address bar from (§3.9, WP-23).
     *
     * A browser outside this list still counts as foreground time if the user guards it as an app;
     * only domain detection needs this list, because only these five have known URL-bar view ids.
     */
    val browsers: List<String> = listOf(
        "com.android.chrome",
        "com.brave.browser",
        "com.microsoft.emmx",
        "org.mozilla.firefox",
        "com.mi.globalbrowser",
    )

    /**
     * The apps that are never guarded, whatever the settings say (§3.6).
     *
     * A phone is allowed to interrupt a feed; it is not allowed to interrupt a phone call, a camera,
     * the Settings app the user is using to fix Sadiq, or the permission dialog that is asking on
     * Sadiq's own behalf. Rafiq is here by name because one phone runs both apps and Sadiq must
     * never stand between the user and it.
     *
     * Launcher and IME packages appear here only as fallbacks the platform usually overrides: the
     * authoritative launcher and the authoritative keyboards come from [neverGuard]'s runtime
     * arguments, because a ROM may ship a launcher this list has never heard of.
     */
    val baseNeverGuard: Set<String> = setOf(
        // Telephony and messaging.
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.android.mms",
        "com.google.android.apps.messaging",
        // Camera and clock.
        "com.android.camera",
        "com.google.android.GoogleCamera",
        "com.android.deskclock",
        "com.google.android.deskclock",
        // The system itself.
        "com.android.settings",
        "com.android.systemui",
        "com.miui.home",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        // Xiaomi's security centre and the dialogs that ask for permissions.
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        // Keyboards: the enabled ones are added at runtime, these are the common shipping ones.
        "com.google.android.inputmethod.latin",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_miui",
        "com.iflytek.inputmethod.miui",
        // The other app on this phone.
        "com.rafiq.app",
    )

    /**
     * The complete never-guard set: the static table plus the parts only the device knows.
     *
     * @param ownPackage Sadiq's own package, read from `context.packageName` and never hardcoded
     *   (name addendum §2.5).
     * @param defaultHomePackage the resolved `CATEGORY_HOME` package, or null when the device has
     *   none.
     * @param enabledImePackages every enabled input method's package.
     * @return the packages the foreground resolver must treat as transparent.
     */
    fun neverGuard(
        ownPackage: String,
        defaultHomePackage: String?,
        enabledImePackages: Set<String>,
    ): Set<String> {
        val resolved = LinkedHashSet<String>(baseNeverGuard.size + enabledImePackages.size + 2)
        resolved.addAll(baseNeverGuard)
        resolved.add(ownPackage)
        if (!defaultHomePackage.isNullOrBlank()) resolved.add(defaultHomePackage)
        resolved.addAll(enabledImePackages.filter { it.isNotBlank() })
        return resolved
    }

    /** Whether [packageName] is one of the known feeds, i.e. suggested first in the picker. */
    fun isKnownFeed(packageName: String): Boolean = packageName in knownFeeds

    /**
     * Where [packageName] sits in [knownFeeds], or [Int.MAX_VALUE] when it is not a known feed.
     *
     * Used as a sort key: smaller first, unknown apps keep their relative order.
     */
    fun feedRank(packageName: String): Int {
        val index = knownFeeds.indexOf(packageName)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    /** Whether [packageName] is a browser whose address bar the web guard can read. */
    fun isBrowser(packageName: String): Boolean = packageName in browsers

    /**
     * A stable sort that puts the known feeds first, in [knownFeeds] order, and leaves everything
     * else in the order it arrived in.
     *
     * The picker shows two sections and needs this for the first one; sorting by label alone would
     * scatter Instagram, YouTube and X among the other apps, which is exactly the list the user is
     * being asked about.
     *
     * @param items the list to order.
     * @param packageOf how to read a package name out of an item.
     * @return a new list; [items] is not modified.
     */
    fun <T> knownFeedsFirst(items: List<T>, packageOf: (T) -> String): List<T> =
        items.sortedWith(compareBy<T> { item -> feedRank(packageOf(item)) })
}
