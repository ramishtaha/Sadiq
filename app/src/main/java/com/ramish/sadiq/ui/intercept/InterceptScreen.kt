package com.ramish.sadiq.ui.intercept

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.core.engine.Choice
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.PrayerName
import com.ramish.sadiq.ui.components.ComponentPreview
import com.ramish.sadiq.ui.components.PrimaryPillButton
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import java.util.concurrent.TimeUnit

/**
 * @Composable fun InterceptScreen(ui: InterceptUi, onChoice: (Choice) -> Unit, appLabel: String)
 */
@Composable
fun InterceptScreen(
    ui: InterceptUi,
    onChoice: (Choice) -> Unit,
    appLabel: String,
    appIcon: @Composable () -> Unit,
    interceptCopy: InterceptCopy
) {
    val copy = interceptCopy
    val scrollState = rememberScrollState()

    SadiqTheme(flavor = if (ui.forceDark) ThemeFlavor.DARK else ThemeFlavor.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = Spacing.xl)
                    .widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(32.dp)) // Top (32 dp from the top)

                // App chip
                AppChip(packageName = ui.packageName, label = appLabel)

                Spacer(Modifier.height(Spacing.xl)) // 20 dp gap

                // Middle content (glyph, headline, subtitle, reflect card)
                InterceptGlyph(ui = ui)
                Spacer(Modifier.height(Spacing.md)) // 12 dp gap
                InterceptHeadline(text = copy.headline(ui))
                Spacer(Modifier.height(Spacing.md)) // 12 dp gap

                if (ui.kind == SurfaceKind.REFLECT) {
                    ReflectCard(ui = ui, onReasonSelected = { reason ->
                        onChoice(Choice.Reflect(reason))
                    }, copy = copy)
                } else {
                    copy.subtitle(ui)?.let { subtitle ->
                        InterceptSubtitle(text = subtitle)
                    }
                }

                Spacer(Modifier.weight(1f)) // Middle (weight 1, centred)

                // Bottom content (primary, secondary, tertiary, caption)
                PrimaryActionButton(
                    text = copy.primaryButton(ui),
                    onClick = {
                        when (ui.kind) {
                            SurfaceKind.PAUSE, SurfaceKind.REFLECT -> onChoice(Choice.Leave)
                            SurfaceKind.PRAYER -> onChoice(Choice.Stay(5)) // Default stay for prayer
                            SurfaceKind.MORNING -> onChoice(Choice.StartFocusNow(5))
                            SurfaceKind.COOLDOWN, SurfaceKind.SLEEP_GATE, SurfaceKind.TIME_UP, SurfaceKind.TEST -> onChoice(Choice.Leave)
                            SurfaceKind.FOCUS_GUARD -> onChoice(Choice.BackToFocus)
                            else -> { /* No primary action for other kinds */ }
                        }
                    },
                    leaveEarnsStar = ui.leaveEarnsStar && ui.kind != SurfaceKind.TEST
                )

                copy.secondaryButton(ui)?.let { secondaryButtonLabel ->
                    Spacer(Modifier.height(Spacing.md)) // 12 dp gap
                    when (ui.kind) {
                        SurfaceKind.PAUSE, SurfaceKind.REFLECT, SurfaceKind.PRAYER -> {
                            SecondaryActionButton(
                                text = secondaryButtonLabel,
                                onClick = { /* Will open chips */ },
                                showChips = true,
                                chipOptions = ui.stayOptionsMin,
                                onChipSelected = { minutes -> onChoice(Choice.Stay(minutes)) }
                            )
                        }
                        SurfaceKind.MORNING -> {
                            PrimaryActionButton(
                                text = secondaryButtonLabel,
                                onClick = { onChoice(Choice.Leave) },
                                leaveEarnsStar = ui.leaveEarnsStar
                            )
                        }
                        SurfaceKind.SLEEP_GATE -> {
                            PrimaryActionButton(
                                text = secondaryButtonLabel,
                                onClick = { onChoice(Choice.WindDown) },
                                leaveEarnsStar = false // Wind down does not earn a star
                            )
                        }
                        SurfaceKind.FOCUS_GUARD -> {
                            PrimaryActionButton(
                                text = secondaryButtonLabel,
                                onClick = { onChoice(Choice.EndFocus) },
                                leaveEarnsStar = false // Ending focus does not earn a star directly
                            )
                        }
                        SurfaceKind.TIME_UP -> {
                            SecondaryActionButton(
                                text = secondaryButtonLabel,
                                onClick = { /* Will open chips */ },
                                showChips = true,
                                chipOptions = ui.stayOptionsMin,
                                onChipSelected = { minutes -> onChoice(Choice.Stay(minutes)) }
                            )
                        }
                        else -> { /* No secondary button for other kinds */ }
                    }
                }

                copy.tertiaryButton(ui)?.let { tertiaryButtonLabel ->
                    Spacer(Modifier.height(Spacing.md)) // 8 dp gap
                    TertiaryActionButton(
                        text = tertiaryButtonLabel,
                        onClick = {
                            when (ui.kind) {
                                SurfaceKind.PAUSE, SurfaceKind.REFLECT -> onChoice(Choice.FreeScroll(TimeUnit.MILLISECONDS.toMinutes(ui.freeScrollAvailableMs ?: 0L).toInt()))
                                SurfaceKind.COOLDOWN, SurfaceKind.SLEEP_GATE, SurfaceKind.FOCUS_GUARD -> onChoice(Choice.QuickNeed)
                                SurfaceKind.TIME_UP -> onChoice(Choice.FreeScroll(TimeUnit.MILLISECONDS.toMinutes(ui.freeScrollAvailableMs ?: 0L).toInt()))
                                else -> { /* No tertiary action for other kinds */ }
                            }
                        },
                        requiresHold = (ui.kind == SurfaceKind.COOLDOWN || ui.kind == SurfaceKind.SLEEP_GATE || ui.kind == SurfaceKind.FOCUS_GUARD) && (ui.quickNeedsLeft ?: 0 > 0)
                    )
                }

                copy.caption(ui)?.let { caption ->
                    Spacer(Modifier.height(Spacing.md)) // 12 dp gap
                    InterceptCaption(text = caption)
                }

                Spacer(Modifier.height(20.dp)) // 20 dp above the nav bar
            }
        }
    }
}

// Mock implementation for previews
@Preview(name = "InterceptScreen · PAUSE · Light", widthDp = 360, showBackground = true)
@Composable
private fun InterceptScreenPauseLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        InterceptScreen(
            ui = InterceptUi(
                kind = SurfaceKind.PAUSE,
                packageName = "com.instagram.android",
                domain = null,
                suggestion = null,
                stayOptionsMin = listOf(5, 10, 15),
                stayRequiresHold = false,
                resumeRemainingMs = null,
                freeScrollOptionsMin = listOf(5, 10),
                freeScrollAvailableMs = TimeUnit.MINUTES.toMillis(8),
                quickNeedsLeft = 3,
                budgetRemainingMs = TimeUnit.MINUTES.toMillis(25),
                restRemainingMs = null,
                restEndsWall = null,
                focusRemainingMs = null,
                prayer = null,
                isJumuah = false,
                nextFajrWall = null,
                windowEndWall = null,
                reflectionOpens = null,
                reflectReason = null,
                timeUpKind = null,
                leaveEarnsStar = true,
                forceDark = false,
                testMode = false,
                mode = Mode.BALANCED
            ),
            onChoice = {},
            appLabel = "Instagram",
            appIcon = {},
            interceptCopy = InterceptCopy(LocalContext.current)
        )
    }
}
