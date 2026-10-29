package com.ramish.sadiq.ui.intercept

import android.graphics.drawable.Drawable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Mosque
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.R
import com.ramish.sadiq.core.engine.Choice
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.Suggestion
import com.ramish.sadiq.core.insight.SuggestionAction
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.PrayerName
import com.ramish.sadiq.ui.components.AppIcon
import com.ramish.sadiq.ui.components.ChoiceChips
import com.ramish.sadiq.ui.components.HoldToConfirmButton
import com.ramish.sadiq.ui.components.LocalAppIconLoader
import com.ramish.sadiq.ui.components.PrimaryPillButton
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.TextPillButton
import com.ramish.sadiq.ui.components.TextPillVariant
import com.ramish.sadiq.ui.components.TonalPillButton
import com.ramish.sadiq.ui.components.ComponentPreview
import com.ramish.sadiq.ui.components.currentThemeFlavor
import com.ramish.sadiq.ui.theme.SemanticColors
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import java.util.concurrent.TimeUnit

/** App chip at the top of the surface. */
@Composable
fun AppChip(packageName: String, label: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(packageName = packageName, size = 20.dp)
            Spacer(Modifier.width(Spacing.sm))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The hero glyph. */
@Composable
fun InterceptGlyph(ui: InterceptUi, modifier: Modifier = Modifier) {
    val tint = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT, SurfaceKind.TIME_UP -> MaterialTheme.colorScheme.primary
        SurfaceKind.SLEEP_GATE -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onBackground
    }
    val imageVector: ImageVector = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT, SurfaceKind.TIME_UP -> Icons.Filled.Star
        SurfaceKind.PRAYER -> Icons.Rounded.Mosque
        SurfaceKind.MORNING -> Icons.Filled.WbTwilight
        SurfaceKind.COOLDOWN -> Icons.Filled.HourglassBottom
        SurfaceKind.SLEEP_GATE -> Icons.Filled.Star
        SurfaceKind.FOCUS_GUARD -> Icons.Rounded.CenterFocusStrong
        SurfaceKind.TEST -> Icons.Filled.Star
    }
    Icon(
        imageVector = imageVector,
        contentDescription = null, // The headline describes the icon.
        modifier = modifier.size(56.dp),
        tint = tint
    )
}

/** Headline on the surface. */
@Composable
fun InterceptHeadline(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
    )
}

/** Subtitle on the surface. */
@Composable
fun InterceptSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Spacing.lg)
    )
}

/** Caption at the bottom of the surface. */
@Composable
fun InterceptCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center
    )
}

/** Reflection card inside a REFLECT surface. */
@Composable
fun ReflectCard(
    ui: InterceptUi,
    onReasonSelected: (ReflectionReason) -> Unit,
    onStartFocus: () -> Unit,
    copy: InterceptCopy,
) {
    // ChoiceChips takes a plain `(T) -> String`, so the chip labels are resolved here.
    val context = LocalContext.current
    SadiqCard(
        containerColor = SemanticColors.warning(currentThemeFlavor()),
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentPadding = PaddingValues(
            start = Spacing.xl + 24.dp,
            end = Spacing.xl,
            top = Spacing.xl,
            bottom = Spacing.xl
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            copy.subtitle(ui)?.let { InterceptSubtitle(text = it) }
            Spacer(Modifier.height(Spacing.md))
            // The REFLECT UI shows chips for reflection reasons.
            ChoiceChips(
                options = ReflectionReason.values().toList(),
                selected = ui.reflectReason,
                onSelect = onReasonSelected,
                label = { reason -> context.getString(copy.reflectionChipLabelRes(reason)) }
            )
            if (ui.reflectReason != null && ui.suggestion?.action == SuggestionAction.START_FOCUS_5) {
                Spacer(Modifier.height(Spacing.lg))
                // The pill does what it says: it starts the five-minute focus, from this surface.
                PrimaryPillButton(
                    text = stringResource(R.string.intercept_reflect_action_start_focus_5),
                    onClick = onStartFocus,
                    fillWidth = false
                )
            }
        }
    }
}

/** Primary action button. */
@Composable
fun PrimaryActionButton(text: String, onClick: () -> Unit, leaveEarnsStar: Boolean) {
    PrimaryPillButton(
        text = text,
        onClick = onClick,
        icon = if (leaveEarnsStar) Icons.Filled.Star else null,
        iconContentDescription = if (leaveEarnsStar) stringResource(R.string.intercept_leave_star_content_description) else null
    )
}

/** Secondary action button: the plain tonal pill for a second answer that submits at once. */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
) {
    TonalPillButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        iconContentDescription = iconContentDescription
    )
}

/**
 * The "yes, for how long" pill: a tonal button that expands into the stay chips.
 *
 * The pill's own tap is a real action — it opens the lengths — so it is never a button that does
 * nothing. The chips are the answers, and each one submits the minutes printed on it.
 */
@Composable
fun StayOptionsButton(
    text: String,
    options: List<Int>,
    header: String,
    chipLabel: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TonalPillButton(
            text = text,
            onClick = { expanded = !expanded }
        )
        if (expanded) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = header,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            ChoiceChips(
                options = options,
                selected = null, // Nothing is chosen until a chip is tapped.
                onSelect = onSelect,
                label = chipLabel
            )
        }
    }
}

/** Tertiary action button. */
@Composable
fun TertiaryActionButton(text: String, onClick: () -> Unit, requiresHold: Boolean) {
    if (requiresHold) {
        HoldToConfirmButton(text = text, onConfirm = onClick)
    } else {
        TextPillButton(text = text, onClick = onClick, variant = TextPillVariant.ACCENT)
    }
}

@Preview(name = "AppChip · Light", widthDp = 360, showBackground = true)
@Composable
private fun AppChipLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        AppChip(packageName = "com.instagram.android", label = "Instagram")
    }
}

@Preview(name = "InterceptGlyph · PAUSE · Light", widthDp = 360, showBackground = true)
@Composable
private fun InterceptGlyphPauseLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        InterceptGlyph(ui = previewUi(SurfaceKind.PAUSE))
    }
}

@Preview(name = "InterceptGlyph · PRAYER · Light", widthDp = 360, showBackground = true)
@Composable
private fun InterceptGlyphPrayerLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        InterceptGlyph(ui = previewUi(SurfaceKind.PRAYER, prayer = PrayerName.ASR))
    }
}

/** A minimal surface value, for previews only. */
private fun previewUi(kind: SurfaceKind, prayer: PrayerName? = null) = InterceptUi(
    kind = kind,
    packageName = "com.instagram.android",
    domain = null,
    suggestion = null,
    stayOptionsMin = emptyList(),
    stayRequiresHold = false,
    resumeRemainingMs = null,
    freeScrollOptionsMin = emptyList(),
    freeScrollAvailableMs = null,
    quickNeedsLeft = null,
    budgetRemainingMs = null,
    restRemainingMs = null,
    restEndsWall = null,
    focusRemainingMs = null,
    prayer = prayer,
    isJumuah = false,
    nextFajrWall = null,
    windowEndWall = null,
    reflectionOpens = null,
    reflectReason = null,
    timeUpKind = null,
    leaveEarnsStar = false,
    forceDark = false,
    testMode = false,
    mode = Mode.BALANCED
)

@Preview(name = "InterceptHeadline · Light", widthDp = 360, showBackground = true)
@Composable
private fun InterceptHeadlineLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        InterceptHeadline(text = "Honestly, do you need this right now?")
    }
}

@Preview(name = "InterceptSubtitle · Light", widthDp = 360, showBackground = true)
@Composable
private fun InterceptSubtitleLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        InterceptSubtitle(text = "This is a subtitle or suggestion line.")
    }
}

@Preview(name = "InterceptCaption · Light", widthDp = 360, showBackground = true)
@Composable
private fun InterceptCaptionLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        InterceptCaption(text = "This is a caption at the bottom.")
    }
}
