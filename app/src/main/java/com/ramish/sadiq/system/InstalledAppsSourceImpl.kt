package com.ramish.sadiq.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.inputmethod.InputMethodManager
import com.ramish.sadiq.contract.AppInfo
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.core.apps.AppCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The launchable apps, for the picker and the onboarding app step.
 *
 * Only activities that answer `MAIN`/`LAUNCHER` are returned. That is the manifest's own query
 * (`<queries>` in `AndroidManifest.xml`), so Sadiq can list the apps a user could actually open
 * without asking for `QUERY_ALL_PACKAGES` — and a package with no launcher icon is one the user
 * cannot open to scroll, so it is not a guarding target.
 *
 * The list is built on the IO dispatcher because a first query touches every installed package;
 * afterwards the package manager's own cache makes it cheap. Sorted by label, case-insensitively,
 * with the package name as the tie-break so the order is identical on two runs.
 */
@Singleton
class InstalledAppsSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstalledAppsSource {

    override suspend fun launchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        queryLaunchable()
            .asSequence()
            .mapNotNull { resolveInfo -> resolveInfo.activityInfo?.applicationInfo }
            // An app with two launcher activities is still one app; the first row wins, which keeps
            // the label the launcher itself shows.
            .distinctBy { applicationInfo -> applicationInfo.packageName }
            .map { applicationInfo ->
                AppInfo(
                    packageName = applicationInfo.packageName,
                    label = labelOf(applicationInfo.packageName, applicationInfo.loadLabel(packageManager)),
                    isSuggestedFeed = AppCatalog.isKnownFeed(applicationInfo.packageName),
                )
            }
            .sortedWith(
                compareBy<AppInfo, String>(String.CASE_INSENSITIVE_ORDER) { app -> app.label }
                    .thenBy { app -> app.packageName }
            )
            .toList()
    }

    override fun icon(packageName: String): Drawable? =
        runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()

    private val packageManager: PackageManager
        get() = context.packageManager

    /**
     * Queries the launcher activities.
     *
     * The `int` overload is deprecated on API 33 but still the only one below it, and both are the
     * same query; the version check keeps the call site honest about which contract it uses.
     */
    @Suppress("DEPRECATION")
    private fun queryLaunchable(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    /**
     * The label to show, never blank.
     *
     * A package can carry an empty label (broken resources, a stub APK); falling back to the
     * package name keeps the row tappable and identifiable instead of rendering an empty line the
     * user cannot match to an icon.
     */
    private fun labelOf(packageName: String, rawLabel: CharSequence?): String {
        val label = rawLabel?.toString()?.trim().orEmpty()
        return label.ifEmpty { packageName }
    }
}

/**
 * Resolves the runtime half of [AppCatalog.neverGuard]: the launcher this phone actually uses and
 * the keyboards the user actually enabled.
 *
 * `core` cannot read the platform, and the foreground resolver (WP-14) and the app picker (WP-20)
 * both need the same answer, so the resolution lives next to the only other package-manager reader
 * in WP-12. The result is cached because the foreground resolver consults it on the accessibility
 * event path, where the budget is five milliseconds; [invalidate] is for the one moment the set can
 * change underneath a running service (the user picks a new launcher or keyboard, or the service
 * rebinds).
 *
 * A missing launcher or an empty IME list is not an error: `AppCatalog`'s static table already
 * covers the common ones, and a phone with no default home is a phone with bigger problems than
 * Sadiq's guard list.
 */
@Singleton
class NeverGuardResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cache: Set<String>? = null

    /** The complete never-guard set, cached until [invalidate]. */
    fun resolve(): Set<String> {
        cache?.let { cached -> return cached }
        return synchronized(this) {
            cache ?: AppCatalog.neverGuard(
                ownPackage = context.packageName,
                defaultHomePackage = homePackage(),
                enabledImePackages = imePackages(),
            ).also { resolved -> cache = resolved }
        }
    }

    /** Drops the cached set, so the next [resolve] re-reads the launcher and the keyboards. */
    fun invalidate() {
        synchronized(this) { cache = null }
    }

    /** The package of the current `CATEGORY_HOME` activity, or null when none is set. */
    @Suppress("DEPRECATION")
    fun homePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved: ResolveInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolved?.activityInfo?.packageName
    }

    /** Every enabled input method's package. */
    fun imePackages(): Set<String> {
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return emptySet()
        return manager.enabledInputMethodList.mapNotNull { method -> method.packageName }.toSet()
    }
}
