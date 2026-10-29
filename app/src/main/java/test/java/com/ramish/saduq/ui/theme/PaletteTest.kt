package com.ramish.saduq.ui.theme

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

    private fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)
}
