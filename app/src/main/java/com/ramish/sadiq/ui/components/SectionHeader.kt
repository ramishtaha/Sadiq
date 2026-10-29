package com.ramish.sadiq.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/**
 * The small uppercase label that opens a section. It is a label, not a heading: TalkBack should
 * read the content, so no heading semantics are attached. The 0.8 sp tracking is the type scale's
 * label tracking, spelled out here so a typography change cannot silently loosen it.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.fillMaxWidth().padding(top = Spacing.xl, bottom = Spacing.sm),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Preview(name = "SectionHeader · Light", widthDp = 360, showBackground = true)
@Composable
private fun SectionHeaderLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) { SectionHeader("Protected times") }
}

@Preview(name = "SectionHeader · Dark", widthDp = 360, showBackground = true)
@Composable
private fun SectionHeaderDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) { SectionHeader("Protected times") }
}

@Preview(name = "SectionHeader · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun SectionHeaderSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) { SectionHeader("Protected times") }
}
