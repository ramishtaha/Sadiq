package com.ramish.sadiq.review

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ramish.sadiq.AppInitializer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

/**
 * Turns the weekly review on, once per process start.
 *
 * `WeeklyReviewReceiver` ships with `android:enabled="false"` in the manifest — the shell declares
 * every M2 component up front so no work package has to edit that file later. This initializer is
 * the M2 side of that bargain: it enables the component at runtime (the manifest is never touched)
 * and arms the next Sunday 11:00.
 *
 * Running at every process start is what "re-armed on boot" means in practice. Nothing of Sadiq
 * starts itself at boot, but the accessibility service is rebound and `BootReceiver` starts the
 * session service, and either way the process — and therefore `SadiqApp.onCreate`, and therefore
 * this initializer — runs. An alarm does not survive a reboot on its own, so this is the only place
 * that can put it back.
 *
 * Both steps are best-effort: a ROM that refuses to enable a component or to set an alarm must not
 * stop the app from starting. The Week tab works regardless; only the Sunday reminder is missing.
 */
class WeekInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: WeeklyReviewScheduler,
) : AppInitializer {

    override fun start() {
        enableReceiver(context)
        scheduler.schedule()
    }

    companion object {

        private const val TAG = "SQ/Week"

        /**
         * Enable (or disable) the review receiver.
         *
         * The manifest ships it disabled, so `COMPONENT_ENABLED_STATE_DEFAULT` means "off"; asking for
         * ENABLED explicitly is the only way to turn it on, and doing it again is a no-op.
         *
         * @param context any context of the app.
         * @param enabled true to accept the Sunday alarm, false to ignore it.
         * @return true when the package manager accepted the change.
         */
        fun enableReceiver(context: Context, enabled: Boolean = true): Boolean = runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, WeeklyReviewReceiver::class.java),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
            true
        }.getOrElse { refused ->
            Log.w(TAG, "weekly review receiver not enabled: ${refused.message}")
            false
        }
    }
}

/**
 * Registers [WeekInitializer] with the app's startup set. The module lives beside the initializer so
 * the review package owns its only seam into the shell, exactly like a feature's `FeatureEntry`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WeekInitializerModule {

    @Binds
    @IntoSet
    abstract fun weekInitializer(impl: WeekInitializer): AppInitializer
}
