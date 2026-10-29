package com.ramish.sadiq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.contract.ThemeMode
import com.ramish.sadiq.contract.UiPrefs
import com.ramish.sadiq.contract.UiPrefsSource
import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.nav.SadiqNavHost
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.ThemeFlavor
import dagger.hilt.android.AndroidEntryPoint
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The only activity the launcher opens: theme, then the nav shell.
 *
 * It contributes no screen of its own. Everything inside [SadiqNavHost] comes from the registered
 * `Set<FeatureEntry>`, which is why ten packages can add tabs without touching this file.
 *
 * The theme follows the user's [UiPrefsSource] when the data layer is present. Until then the
 * `@BindsOptionalOf` binding is empty and the theme follows the system, so the shell runs on its own.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Every screen registered by a feature. */
    @Inject
    lateinit var entries: Set<@JvmSuppressWildcards FeatureEntry>

    /** The preferences, absent until WP-09 binds them. */
    @Inject
    lateinit var prefsSource: Optional<UiPrefsSource>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SadiqShell(entries = entries, prefsSource = prefsSource.orElse(null))
        }
    }
}

/**
 * The shell: resolve the palette, wrap the nav host in it.
 *
 * @param entries every registered screen.
 * @param prefsSource the preferences, or null before the data layer exists.
 */
@Composable
private fun SadiqShell(entries: Set<FeatureEntry>, prefsSource: UiPrefsSource?) {
    val prefsFlow: Flow<UiPrefs?> = remember(prefsSource) {
        prefsSource?.prefs ?: flowOf<UiPrefs?>(null)
    }
    // One frame of "no preferences yet" is preferable to a blocking read: the splash is still up and
    // the first frame renders in the system palette instead of flashing a default light theme.
    val prefs: UiPrefs? by prefsFlow.collectAsStateWithLifecycle(initialValue = null)

    val flavor = flavorFor(
        mode = prefs?.themeMode ?: ThemeMode.SYSTEM,
        systemDark = isSystemInDarkTheme()
    )

    SadiqTheme(flavor = flavor, reduceMotion = prefs?.reduceMotion ?: false) {
        SadiqNavHost(entries = entries)
    }
}

/**
 * Maps the preference plus the OS setting onto one of the three palettes.
 *
 * @param mode the user's choice; [ThemeMode.SYSTEM] defers to the OS.
 * @param systemDark whether the OS is in dark mode right now.
 */
private fun flavorFor(mode: ThemeMode, systemDark: Boolean): ThemeFlavor = when (mode) {
    ThemeMode.SYSTEM -> if (systemDark) ThemeFlavor.DARK else ThemeFlavor.LIGHT
    ThemeMode.LIGHT -> ThemeFlavor.LIGHT
    ThemeMode.DARK -> ThemeFlavor.DARK
    ThemeMode.SEPIA -> ThemeFlavor.SEPIA
}
