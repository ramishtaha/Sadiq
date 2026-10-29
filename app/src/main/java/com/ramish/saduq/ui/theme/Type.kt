package com.ramish.saduq.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/**
 * Editorial Latin scale: firmer, slightly condensed headlines (negative tracking reads "designed",
 * not "stock Roboto"), open body line-height for calm reading, wider tracking on small labels.
 */
val SaduqTypography = Typography(
    displaySmall = Default.displaySmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = Default.headlineMedium.copy(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = Default.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = Default.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
    ),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Default.labelLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp
    ),
    labelMedium = Default.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp
    ),
    bodyLarge = Default.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Default.bodyMedium.copy(lineHeight = 21.sp)
)
