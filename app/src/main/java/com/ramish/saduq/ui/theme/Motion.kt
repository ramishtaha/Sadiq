package com.ramish.saduq.ui.theme

import androidx.compose.runtime.compositionLocalOf

/**
 * One switch for every animation in the app: the user's in-app preference OR the OS
 * "remove animations" setting (folded together in [SaduqTheme]).
 */
val LocalReduceMotion = compositionLocalOf { false }

/** Named durations, so a "bloom" means the same thing everywhere. */
object Motion {
    const val INSTANT = 0
    const val FAST = 120
    const val NORMAL = 220
    const val SLOW = 360
    /** The leave confirmation bloom, before the sky shows the new star faint. */
    const val BLOOM = 400
}
