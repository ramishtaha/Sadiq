package com.ramish.sadiq.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import com.ramish.sadiq.contract.OemSettingsLauncher
import com.ramish.sadiq.contract.SettingsTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens the settings page a permission step needs, walking the §4.7 chain until something starts.
 *
 * The launcher never reports success it did not have: [open] returns true only after
 * `startActivity` returned, and false only when every candidate failed, which is what lets the
 * wizard stay on its step and offer the manual path instead of pretending the user was taken
 * somewhere.
 *
 * Failures are the normal case on a ROM that renamed a page, so each is treated as "try the next
 * one" rather than as an error: a missing activity, an activity that is no longer exported, and a
 * package the build does not carry all end in the same app-info page.
 */
@Singleton
class OemSettingsLauncherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : OemSettingsLauncher {

    /**
     * Sadiq's own launcher label, read once.
     *
     * `com.miui.powerkeeper` renders it as the title of the battery-saver page; reading it on every
     * open would be a package-manager call per tap for a string that cannot change while the
     * process lives.
     */
    private val appLabel: String by lazy {
        runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }
            .getOrDefault(context.packageName)
    }

    override fun open(target: SettingsTarget): Boolean =
        IntentCandidates.candidates(target).any { candidate -> start(candidate) }

    /**
     * Turns one candidate into an `Intent`.
     *
     * `FLAG_ACTIVITY_NEW_TASK` is required because this runs from a service, a receiver or a
     * view-model's application context, never from an activity.
     */
    private fun intentFor(candidate: IntentCandidate): Intent {
        val intent = Intent()
        candidate.action?.let { action -> intent.action = action }
        if (candidate.componentPackage != null && candidate.componentClass != null) {
            intent.component = ComponentName(candidate.componentPackage, candidate.componentClass)
        }
        if (candidate.data == IntentData.PACKAGE_URI) {
            intent.data = Uri.fromParts("package", context.packageName, null)
        }
        candidate.extras.forEach { (name, extra) -> putExtra(intent, name, extra) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /**
     * Resolves one extra into the type the target activity expects.
     *
     * The `when` is not just a lookup: each branch picks the `Intent.putExtra` overload that matches
     * what the target activity actually reads, so a package name arrives as a string and the uid as
     * an int rather than as a type the activity would silently ignore.
     *
     * `EXTRA_COMPONENT_NAME` is a **string**, not a `ComponentName`. AOSP's and MIUI's
     * `AccessibilityDetailsSettingsFragment` read it with `getStringExtra(...)` and
     * `unflattenFromString(...)`, so a Parcelable arrives as null and the page silently falls back to
     * the general Accessibility list — which is what used to happen to O5 on the AVD.
     */
    private fun putExtra(intent: Intent, name: String, extra: IntentExtra) {
        when (extra) {
            IntentExtra.PackageName -> intent.putExtra(name, context.packageName)
            IntentExtra.PackageLabel -> intent.putExtra(name, appLabel)
            IntentExtra.PackageUid -> intent.putExtra(name, Process.myUid())
            IntentExtra.SelfAccessibilityComponent -> intent.putExtra(
                name,
                ComponentName(context.packageName, IntentCandidates.ACCESSIBILITY_SERVICE_CLASS)
                    .flattenToString(),
            )

            is IntentExtra.Literal -> intent.putExtra(name, extra.value)
        }
    }

    /**
     * Tries one candidate.
     *
     * @return true when an activity was started, false when this ROM cannot show that page.
     */
    private fun start(candidate: IntentCandidate): Boolean = try {
        context.startActivity(intentFor(candidate))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        // The activity exists but is not exported for third-party apps on this build.
        false
    } catch (_: IllegalArgumentException) {
        // A ROM that rejects the package URI or an extra type outright.
        false
    }
}
