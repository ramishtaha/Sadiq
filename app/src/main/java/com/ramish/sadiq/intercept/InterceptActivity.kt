package com.ramish.sadiq.intercept

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.guard.InterceptStateHolder
import com.ramish.sadiq.ui.components.AppIcon
import com.ramish.sadiq.ui.components.LocalAppIconLoader
import com.ramish.sadiq.ui.intercept.InterceptCopy
import com.ramish.sadiq.ui.intercept.InterceptScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The full-screen question, launched from the accessibility service.
 *
 * It renders exactly the same [InterceptScreen] the overlay host renders, so there is one surface
 * implementation and no chance of the two drifting apart. It decides nothing: a choice goes back to
 * the controller as a [GuardInput.Chose], and the reducer decides what happens next.
 *
 * The activity finishes itself as soon as the engine stops publishing a question (answered, the app
 * changed, the screen went off), so it can never sit in front of the user with nothing to say.
 */
@AndroidEntryPoint
class InterceptActivity : ComponentActivity() {

    @Inject
    lateinit var holder: InterceptStateHolder

    @Inject
    lateinit var interceptCopy: InterceptCopy

    @Inject
    lateinit var guardController: GuardController

    @Inject
    lateinit var installedAppsSource: InstalledAppsSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The page colour behind the surface, so the first frame never flashes white over a dark app.
        window.setBackgroundDrawableResource(R.color.page_dark)

        Log.i("SP/Intercept", "activity created")
        setContent {
            val state by holder.ui.collectAsStateWithLifecycle()
            val ui = state
            Log.i("SP/Intercept", "compose: ui=" + (ui?.kind?.name ?: "null"))

            if (ui == null) {
                // One frame of "nothing yet" is normal: the holder publishes just before the launch.
                // Never close from here — closing during composition would race the publish.
                return@setContent
            }

            val appLabel = appLabelFor(ui.packageName)

            CompositionLocalProvider(LocalAppIconLoader provides installedAppsSource::icon) {
                InterceptScreen(
                    ui = ui,
                    onChoice = { choice -> guardController.submit(GuardInput.Chose(choice)) },
                    appLabel = appLabel,
                    appIcon = { AppIcon(packageName = ui.packageName, size = 20.dp) },
                    interceptCopy = interceptCopy
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i("SP/Intercept", "onResume")
        // Report this activity as visible only if the overlay is not already showing.
        if (!holder.overlayRequested.value) {
            holder.reportVisible(viaOverlay = false)
        }
    }

    /** The app's own label, or the package name when the label cannot be read. */
    private fun appLabelFor(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    companion object {
        /** The intent the presenter uses to launch this activity. */
        fun createIntent(context: Context): Intent = Intent(context, InterceptActivity::class.java)
    }
}
