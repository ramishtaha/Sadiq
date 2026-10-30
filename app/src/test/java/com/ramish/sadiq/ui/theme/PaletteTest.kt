package com.ramish.sadiq.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The palette is the brand. If a hex changes, the app stops looking like Rafiq, so every value is
 * pinned here for all three flavours.
 */
class PaletteTest {

    @Test
    fun lightPaletteIsPinned() {
        assertEquals(0xFF1F6E5C, LIGHT_PRIMARY)
        assertEquals(0xFFF4EFE5, LIGHT_BACKGROUND)
        assertEquals(0xFFFFFCF6, LIGHT_SURFACE)
        assertEquals(0xFFEAE3D6, LIGHT_SURFACE_VARIANT)
        assertEquals(0xFF704800, LIGHT_TERTIARY)
        assertEquals(0xFFBA1A1A, LIGHT_ERROR)
        assertEquals(0xFFE2DCCE, LIGHT_OUTLINE_VARIANT)
    }

    @Test
    fun darkPaletteIsPinned() {
        assertEquals(0xFF8AD6BF, DARK_PRIMARY)
        assertEquals(0xFF0F1310, DARK_BACKGROUND)
        assertEquals(0xFF1E2521, DARK_SURFACE)
        assertEquals(0xFF2A302C, DARK_SURFACE_VARIANT)
        assertEquals(0xFF333A35, DARK_OUTLINE_VARIANT)
    }

    @Test
    fun sepiaPaletteIsPinned() {
        assertEquals(0xFF1B6150, SEPIA_PRIMARY)
        assertEquals(0xFFF1E3C6, SEPIA_BACKGROUND)
        assertEquals(0xFFFBF1D9, SEPIA_SURFACE)
        assertEquals(0xFFDECCA4, SEPIA_OUTLINE_VARIANT)
    }

    @Test
    fun semanticColoursArePinned() {
        assertEquals(0xFF1E6F30, SemanticColors.LIGHT_SUCCESS)
        assertEquals(0xFF8A5A00, SemanticColors.LIGHT_WARNING)
        assertEquals(0xFFB3261E, SemanticColors.LIGHT_FAILURE)
        assertEquals(0xFF81C784, SemanticColors.DARK_SUCCESS)
        assertEquals(0xFFFFB74D, SemanticColors.DARK_WARNING)
        assertEquals(0xFFFFB4AB, SemanticColors.DARK_FAILURE)
    }

    @Test
    fun surfacesLiftOffThePage() {
        // Cards must read as raised: surface is lighter than background in the light family...
        assertTrue(LIGHT_SURFACE > LIGHT_BACKGROUND)
        // ...and in dark mode the page is darker than the card.
        assertTrue(DARK_SURFACE > DARK_BACKGROUND)
    }

    @Test
    fun theCostRedIsReadableOnEveryPage() {
        // §A.5: "Open anyway" is the one red that is not an error, so its readability is pinned here next
        // to the values it is drawn with. Ratios are WCAG 2.1, rounded to the two decimals the plan states.
        assertEquals(5.64, contrastRatio(LIGHT_ERROR, LIGHT_BACKGROUND), 0.005, "light page")
        assertEquals(11.03, contrastRatio(DARK_ERROR, DARK_BACKGROUND), 0.005, "Bedtime page")
        assertEquals(5.51, contrastRatio(SEPIA_ERROR, SEPIA_BACKGROUND), 0.005, "sepia page")

        // The hold's sweep is the same red over errorContainer, so that pair has to stay legible too.
        assertTrue(contrastRatio(LIGHT_ERROR, LIGHT_ERROR_CONTAINER) >= AA_NORMAL_TEXT)
        assertTrue(contrastRatio(DARK_ERROR, DARK_ERROR_CONTAINER) >= AA_NORMAL_TEXT)
        assertTrue(contrastRatio(SEPIA_ERROR, SEPIA_ERROR_CONTAINER) >= AA_NORMAL_TEXT)
    }

    /** WCAG 2.1 contrast ratio between two opaque `0xAARRGGBB` colours. */
    private fun contrastRatio(foreground: Long, background: Long): Double {
        val first = relativeLuminance(foreground)
        val second = relativeLuminance(background)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** WCAG 2.1 relative luminance: linearise each channel, then weight them. */
    private fun relativeLuminance(argb: Long): Double {
        val red = linearise((argb shr 16) and 0xFF)
        val green = linearise((argb shr 8) and 0xFF)
        val blue = linearise(argb and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun linearise(channel: Long): Double {
        val srgb = channel / 255.0
        return if (srgb <= 0.03928) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
    }

    private fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)

    private companion object {
        /** WCAG AA for normal-size text. */
        const val AA_NORMAL_TEXT: Double = 4.5
    }
}
