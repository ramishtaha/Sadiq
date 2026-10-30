package com.ramish.sadiq.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Which ink a [HoldToConfirmButton] holds in.
 *
 * [DEFAULT] is the app's own voice: a `surfaceVariant` pill that fills `primaryContainer`. [COST] is the
 * one place a hold costs something — "Open anyway" (§A.5): transparent fill, a 1 dp `error` outline and
 * `error` text, with an `errorContainer` sweep while the finger stays down. It is a red *line*, never a
 * filled red container, and it never tints the page.
 */
enum class HoldTone { DEFAULT, COST }

/**
 * The deliberate answer: a tonal pill that only confirms after a sustained press.
 *
 * The tone's sweep fills left to right over [holdMs] while the finger stays down; letting go early
 * empties it. Completion fires one haptic and calls [onConfirm] once — [HapticFeedbackType.Confirm] for
 * [HoldTone.DEFAULT], [HapticFeedbackType.LongPress] for [HoldTone.COST], because opening anyway is not a
 * confirmation of anything and must not borrow Leave's tick. A disabled button never completes, whatever
 * the ticker is doing.
 *
 * There is no decorative animation to disable, so [com.ramish.sadiq.ui.theme.LocalReduceMotion] does
 * not change the timing: the fill is a progress readout, not an entrance.
 *
 * TalkBack cannot press and hold reliably, so the node exposes a custom action "Confirm" and an
 * equivalent long-click label, which is what produces the "Double-tap and hold" hint.
 *
 * @param tone [HoldTone.COST] for the discouraged door; the default is the everyday pill.
 */
@Composable
fun HoldToConfirmButton(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdMs: Long = HOLD_TO_CONFIRM_MS,
    fillWidth: Boolean = true,
    tone: HoldTone = HoldTone.DEFAULT
) {
    val scheme = MaterialTheme.colorScheme
    val hold = remember(holdMs) { HoldProgress(holdMs) }
    var state by remember(holdMs) { mutableStateOf(HoldState()) }
    val haptics = LocalHapticFeedback.current
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    val gestureScope = rememberCoroutineScope()
    val fraction = hold.fraction(state)

    val cost = tone == HoldTone.COST
    // COST is smaller than the primary above it: 48 dp, a line rather than a block (§A.5).
    val minHeight = if (cost) 48.dp else 56.dp
    val fill: Color = when {
        cost -> Color.Transparent
        enabled -> scheme.surfaceVariant
        else -> scheme.onSurface.copy(alpha = 0.12f)
    }
    val border: BorderStroke? = when {
        !cost -> null
        enabled -> BorderStroke(1.dp, scheme.error)
        else -> BorderStroke(1.dp, scheme.outlineVariant)
    }
    val ink = when {
        cost && enabled -> scheme.error
        cost -> scheme.outline
        enabled -> scheme.onSurface
        else -> scheme.onSurface.copy(alpha = 0.38f)
    }
    val sweep = if (cost) scheme.errorContainer else scheme.primaryContainer
    val completionHaptic = if (cost) HapticFeedbackType.LongPress else HapticFeedbackType.Confirm

    val confirmed: () -> Boolean = {
        if (enabled) {
            haptics.performHapticFeedback(completionHaptic)
            currentOnConfirm()
            true
        } else {
            false
        }
    }

    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = minHeight)
            .clip(CircleShape)
            .background(fill)
            .then(if (border != null) Modifier.border(border, CircleShape) else Modifier)
            .pointerInput(holdMs, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        var held = hold.start()
                        state = held
                        val ticker = gestureScope.launch {
                            // Measure from the first frame, not by summing per-frame deltas: integer
                            // millisecond truncation on every frame would otherwise add up and make
                            // the hold longer than the 2 s it promises.
                            val startFrameNanos = withFrameNanos { it }
                            while (isActive && !held.completed) {
                                val frameNanos = withFrameNanos { it }
                                val elapsedMs = (frameNanos - startFrameNanos) / 1_000_000L
                                val tick = hold.advance(
                                    state = held,
                                    deltaMs = elapsedMs - held.elapsedMs,
                                    enabled = enabled,
                                )
                                held = tick.state
                                state = held
                                if (tick.justCompleted) {
                                    haptics.performHapticFeedback(completionHaptic)
                                    currentOnConfirm()
                                    break
                                }
                            }
                        }
                        tryAwaitRelease()
                        ticker.cancel()
                        if (!held.completed) {
                            state = hold.release(held)
                        }
                    }
                )
            }
            .semantics(mergeDescendants = true) {
                customActions = listOf(CustomAccessibilityAction(label = "Confirm", action = confirmed))
                onLongClick(label = "Confirm", action = confirmed)
            },
        contentAlignment = Alignment.Center
    ) {
        if (fraction > 0f) {
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(sweep)
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            modifier = Modifier.padding(horizontal = Spacing.xl)
        )
    }
}

@Preview(name = "HoldToConfirmButton · Light", widthDp = 360, showBackground = true)
@Composable
private fun HoldToConfirmButtonLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        HoldToConfirmButton(text = "Hold for one minute · 2 left", onConfirm = {})
    }
}

@Preview(name = "HoldToConfirmButton · Dark", widthDp = 360, showBackground = true)
@Composable
private fun HoldToConfirmButtonDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        HoldToConfirmButton(text = "Hold for one minute · 2 left", onConfirm = {})
    }
}

@Preview(name = "HoldToConfirmButton · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun HoldToConfirmButtonSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        HoldToConfirmButton(text = "Hold for one minute · 2 left", onConfirm = {})
    }
}

@Preview(name = "HoldToConfirmButton COST · Light", widthDp = 360, showBackground = true)
@Composable
private fun HoldToConfirmButtonCostLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        HoldToConfirmButton(text = "Hold to open · 10 min", onConfirm = {}, tone = HoldTone.COST)
        HoldToConfirmButton(text = "Pick one above first", onConfirm = {}, enabled = false, tone = HoldTone.COST)
    }
}

@Preview(name = "HoldToConfirmButton COST · Dark", widthDp = 360, showBackground = true)
@Composable
private fun HoldToConfirmButtonCostDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        HoldToConfirmButton(text = "Hold to open · 10 min", onConfirm = {}, tone = HoldTone.COST)
        HoldToConfirmButton(text = "Pick one above first", onConfirm = {}, enabled = false, tone = HoldTone.COST)
    }
}

@Preview(name = "HoldToConfirmButton COST · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun HoldToConfirmButtonCostSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        HoldToConfirmButton(text = "Hold to open · 10 min", onConfirm = {}, tone = HoldTone.COST)
        HoldToConfirmButton(text = "Pick one above first", onConfirm = {}, enabled = false, tone = HoldTone.COST)
    }
}
