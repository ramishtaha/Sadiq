package com.ramish.saduq.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Status colours that are not part of Material's [androidx.compose.material3.ColorScheme].
 * Each theme family gets its own trio, contrast-checked against that family's background, so a
 * warning never borrows Material's red and an error never washes out on the warm page.
 */
object SemanticColors {
    const val LIGHT_SUCCESS = 0xFF1E6F30
    const val LIGHT_WARNING = 0xFF8A5A00
    const val LIGHT_FAILURE = 0xFFB3261E
    const val DARK_SUCCESS = 0xFF81C784
    const val DARK_WARNING = 0xFFFFB74D
    const val DARK_FAILURE = 0xFFFFB4AB
    const val SEPIA_SUCCESS = 0xFF1B5E20
    const val SEPIA_WARNING = 0xFF704800
    const val SEPIA_FAILURE = 0xFFA1331B

    fun success(flavor: ThemeFlavor): Color = when (flavor) {
        ThemeFlavor.LIGHT -> Color(LIGHT_SUCCESS)
        ThemeFlavor.DARK -> Color(DARK_SUCCESS)
        ThemeFlavor.SEPIA -> Color(SEPIA_SUCCESS)
    }

    fun warning(flavor: ThemeFlavor): Color = when (flavor) {
        ThemeFlavor.LIGHT -> Color(LIGHT_WARNING)
        ThemeFlavor.DARK -> Color(DARK_WARNING)
        ThemeFlavor.SEPIA -> Color(SEPIA_WARNING)
    }

    fun failure(flavor: ThemeFlavor): Color = when (flavor) {
        ThemeFlavor.LIGHT -> Color(LIGHT_FAILURE)
        ThemeFlavor.DARK -> Color(DARK_FAILURE)
        ThemeFlavor.SEPIA -> Color(SEPIA_FAILURE)
    }
}
