package com.ramish.sadiq.ui.components

import androidx.compose.foundation.background
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
 * The deliberate answer: a tonal pill that only confirms after a sustained press.
 *
 * A `primaryContainer` fill sweeps left to right over [holdMs] while the finger stays down; letting
 * go early empties it. Completion fires [HapticFeedbackType.Confirm] once and calls [onConfirm] once.
 * There is no decorative animation to disable, so [com.ramish.sadiq.ui.theme.LocalReduceMotion] does
 * not change the timing: the fill is a progress readout, not an entrance.
 *
 * TalkBack cannot press and hold reliably, so the node exposes a custom action "Confirm" and an
 * equivalent long-click label, which is what produces the "Double-tap and hold" hint.
 */
@Composable
fun HoldToConfirmButton(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdMs: Long = HOLD_TO_CONFIRM_MS,
    fillWidth: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val hold = remember(holdMs) { HoldProgress(holdMs) }
    var state by remember(holdMs) { mutableStateOf(HoldState()) }
    val haptics = LocalHapticFeedback.current
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    val gestureScope = rememberCoroutineScope()
    val fraction = hold.fraction(state)

    val confirmed: () -> Boolean = {
        if (enabled) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            currentOnConfirm()
            true
        } else {
            false
        }
    }

    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(if (enabled) scheme.surfaceVariant else scheme.onSurface.copy(alpha = 0.12f))
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
                                    deltaMs = elapsedMs - held.elapsedMs
                                )
                                held = tick.state
                                state = held
                                if (tick.justCompleted) {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
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
                        .background(scheme.primaryContainer)
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f),
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
