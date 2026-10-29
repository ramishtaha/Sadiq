package com.ramish.sadiq.system

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.ramish.sadiq.contract.Tri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two HyperOS permissions that have no public API: "Display pop-up windows while running in the
 * background" and "Autostart".
 *
 * MIUI and HyperOS answer them through OEM-only app-op numbers, which the public `AppOpsManager`
 * API cannot name. The alternatives are worse than reflection:
 *
 * - Reading the settings database directly needs a permission Sadiq must not ask for, and the
 *   column names differ per ROM.
 * - Guessing from "did the activity appear" costs a real interruption and would be measured while
 *   the user is being asked to grant it.
 *
 * So this class reflects on `AppOpsManager.checkOpNoThrow(int, int, String)`, the same call MIUI's
 * own Security Center uses. Every failure mode — a non-Xiaomi ROM, a hidden-API block, a build that
 * moved the method, a null result — answers [Tri.UNKNOWN]. That is the honest answer: Sadiq did not
 * learn the value, and the wizard falls back to the user's own confirmation instead of pretending
 * the permission is off (or on).
 */
@Singleton
class MiuiAppOps @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The reflected method, resolved once.
     *
     * `null` means this ROM does not expose it, which is a permanent answer for the process; the
     * lookup is not retried per call. Hidden-API enforcement throws from `getMethod` on some
     * builds, hence the `runCatching`.
     */
    private val checkOpNoThrow: Method? by lazy {
        runCatching {
            AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Integer.TYPE,
                Integer.TYPE,
                String::class.java,
            )
        }.getOrNull()
    }

    /** Whether the two OEM app-ops can mean anything on this ROM. */
    fun isSupported(): Boolean =
        HyperOs.isXiaomiFamily(Build.MANUFACTURER, Build.BRAND)

    /**
     * Reads one OEM app-op for Sadiq's own uid.
     *
     * @param op the MIUI app-op number, e.g. [OP_BACKGROUND_POPUPS].
     * @return [Tri.YES] when the ROM says allowed, [Tri.NO] when it says ignored or errored, and
     *   [Tri.UNKNOWN] for every other answer — including "default", which means the user has never
     *   decided and the system default applies.
     */
    fun check(op: Int): Tri {
        if (!isSupported()) return Tri.UNKNOWN
        val method = checkOpNoThrow ?: return Tri.UNKNOWN
        return try {
            val manager = context.getSystemService(AppOpsManager::class.java) ?: return Tri.UNKNOWN
            val mode = method.invoke(manager, op, Process.myUid(), context.packageName) as? Int
                ?: return Tri.UNKNOWN
            triForMode(mode)
        } catch (_: Throwable) {
            // InvocationTargetException, IllegalAccessException, SecurityException and anything a
            // ROM invents: all of them mean "the value is unknown", never a guess.
            Tri.UNKNOWN
        }
    }

    /** "Display pop-up windows while running in the background" (O7). */
    fun backgroundPopups(): Tri = check(OP_BACKGROUND_POPUPS)

    /** "Autostart" (O10). */
    fun autostart(): Tri = check(OP_AUTOSTART)

    companion object {

        /**
         * MIUI app-op "display pop-up windows while running in the background".
         *
         * The number is an OEM constant, not part of the SDK; it has been stable from MIUI 11
         * through HyperOS 3.
         */
        const val OP_BACKGROUND_POPUPS: Int = 10021

        /** MIUI app-op "autostart". */
        const val OP_AUTOSTART: Int = 10008

        /**
         * Maps an `AppOpsManager` mode to the snapshot's three-valued answer.
         *
         * Pure and public so the mapping is tested without a Xiaomi phone. `MODE_FOREGROUND` is
         * [Tri.UNKNOWN] rather than [Tri.NO] on purpose: it means "allowed while in the foreground",
         * which says nothing about the background launch this permission exists for.
         *
         * @param mode `AppOpsManager`'s mode for the op.
         */
        fun triForMode(mode: Int): Tri = when (mode) {
            AppOpsManager.MODE_ALLOWED -> Tri.YES
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> Tri.NO
            else -> Tri.UNKNOWN
        }
    }
}
