package com.ramish.sadiq.ui.intercept

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.R
import com.ramish.sadiq.core.engine.Choice
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.engine.SurfaceKind.PAUSE
import com.ramish.sadiq.core.engine.SurfaceKind.REFLECT
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.ui.components.ChoiceChips
import com.ramish.sadiq.ui.components.ComponentPreview
import com.ramish.sadiq.ui.components.HoldTone
import com.ramish.sadiq.ui.components.HoldToConfirmButton
import com.ramish.sadiq.ui.components.TextPillButton
import com.ramish.sadiq.ui.components.TextPillVariant
import com.ramish.sadiq.ui.theme.LocalReduceMotion
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * The question, exactly as the engine described it in [ui].
 *
 * Three rules from the plan live here and nowhere else:
 *
 * - **Leave is always answerable.** The primary pill says what leaving does, and Back is the same
 *   answer ([BackHandler]), so no surface can be escaped without telling the engine.
 * - **Every option submits the [Choice] its label names.** The mapping is [InterceptCopy.primaryChoice],
 *   [InterceptCopy.secondaryChoice] and [InterceptCopy.tertiaryChoice]; the screen never invents one.
 * - **Nothing dead is drawn.** An option whose label is null is not rendered, and the values on the
 *   labels come from the surface itself, so a chip can never offer minutes the engine would refuse.
 *
 * The "Open anyway" door has two shapes on this one surface (§A.2, §A.5). The **entry** is the last
 * element of a surface the engine offers it on: a red text pill under the caption. Tapping it sends
 * [Choice.ArmOverride], and the engine switches this same surface to the **confirm view**: a hollow
 * star, the cost in words, seven reasons, and the hold. Which one is up is [InterceptUi.overrideArmedElapsed]
 * and nothing local, so Back, the overlay fallback and a recreated activity all behave the same way.
 *
 * @param ui the surface to draw.
 * @param onChoice every answer, sent to the engine by the host exactly once.
 * @param appLabel the app's own label, named in the prayer, test and door lines.
 * @param appIcon the app icon slot (the chip draws it through [AppChip]).
 * @param interceptCopy the copy rules.
 * @param grantedMinutes TIME_UP only: the length of the grant that ran out, so the headline can
 *   say "That's the 5 minutes you chose." instead of "That's the 0 minutes you chose."
 */
@Composable
fun InterceptScreen(
    ui: InterceptUi,
    onChoice: (Choice) -> Unit,
    appLabel: String,
    appIcon: @Composable () -> Unit,
    interceptCopy: InterceptCopy,
    grantedMinutes: Int? = null
) {
    val copy = interceptCopy
    val scrollState = rememberScrollState()
    val nowWall = rememberWallClock(ui.kind)
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current

    // The confirm view is the engine's armed state, never a local flag: the wait below is measured
    // from `overrideArmedElapsed` with the same `elapsedRealtime` clock, so a recreated activity
    // resumes the countdown where it really is and nothing in the UI can shorten it (OV-4.4).
    val armed = ui.overrideArmedElapsed != null
    var pickedReason by remember(ui.overrideArmedElapsed) { mutableStateOf<ReflectionReason?>(null) }
    val remainingMs = rememberOverrideWait(ui)
    val waitState = overrideWaitState(remainingMs, reasonPicked = pickedReason != null)

    // True for the 400 ms after Leave: the confirmation replaces the question for one beat. It is
    // a local flourish only — the answer itself is sent in the same tap, so a fast Home press can
    // never swallow it — and it reverts on its own if the surface somehow stays up.
    var confirmingLeave by remember { mutableStateOf(false) }

    val leave: () -> Unit = {
        if (playsLeaveConfirmation(ui.kind) && !reduceMotion) confirmingLeave = true
        onChoice(Choice.Leave)
    }

    LaunchedEffect(confirmingLeave) {
        if (confirmingLeave) {
            delay(LEAVE_CONFIRM_MS)
            confirmingLeave = false
        }
    }

    // TalkBack hears the wait twice: once when it starts and once when it is over — never on every
    // tick. The countdown is plain text and deliberately not a live region (§A.5).
    LaunchedEffect(ui.overrideArmedElapsed) {
        if (armed) {
            view.announceForAccessibility(
                copy.overrideWaitLabel(OverrideWaitState.WAITING, remainingMs)
            )
        }
    }
    val waitOver = waitState != OverrideWaitState.WAITING
    LaunchedEffect(waitOver) {
        if (armed && waitOver) view.announceForAccessibility(copy.overrideWaitLabel(waitState))
    }

    // Back = Leave (§4.4). Without this, Back finishes the activity and leaves the engine in
    // PROMPTING with nothing draining. On the confirm view it is the same answer: the big primary
    // says "No, take me home" and Back does exactly that.
    BackHandler { leave() }

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
                Spacer(Modifier.height(32.dp))

                // App chip
                AppChip(packageName = ui.packageName, label = appLabel)

                Spacer(Modifier.height(Spacing.xl))

                // Middle content (glyph, headline, subtitle, check-in card or the door's cost line)
                val glyphScale by animateFloatAsState(
                    targetValue = if (confirmingLeave) 1.4f else 1f,
                    animationSpec = tween(durationMillis = LEAVE_CONFIRM_MS.toInt())
                )
                InterceptGlyph(
                    ui = ui,
                    armedOverride = armed && !confirmingLeave,
                    modifier = Modifier.scale(glyphScale)
                )
                Spacer(Modifier.height(Spacing.md))

                InterceptHeadline(
                    text = when {
                        confirmingLeave -> copy.leaveConfirmation()
                        armed -> copy.overrideHeadline(appLabel)
                        else -> copy.headline(ui = ui, nowWall = nowWall, grantedMinutes = grantedMinutes)
                    }
                )
                Spacer(Modifier.height(Spacing.md))

                if (!confirmingLeave) {
                    when {
                        // The confirm view replaces the subtitle and the middle with the cost line
                        // and the reasons (§A.6).
                        armed -> OverrideReasonCard(
                            ui = ui,
                            copy = copy,
                            picked = pickedReason,
                            onPick = { reason -> pickedReason = reason }
                        )

                        ui.kind == SurfaceKind.REFLECT -> ReflectCard(
                            ui = ui,
                            onReasonSelected = { reason -> onChoice(Choice.Reflect(reason)) },
                            onStartFocus = { onChoice(copy.startFocusFiveChoice()) },
                            copy = copy
                        )

                        else -> copy.subtitle(ui = ui, appLabel = appLabel, nowWall = nowWall)?.let { subtitle ->
                            InterceptSubtitle(text = subtitle)
                        }
                    }
                }

                Spacer(Modifier.weight(1f)) // Middle (weight 1, centred)

                // Bottom content (primary, secondary, chips, caption, and the door)
                if (!confirmingLeave) {
                    if (armed) {
                        OverrideOpenActions(
                            ui = ui,
                            copy = copy,
                            appLabel = appLabel,
                            waitState = waitState,
                            remainingMs = remainingMs,
                            pickedReason = pickedReason,
                            onAnswer = onChoice,
                            onLeave = leave
                        )
                    } else {
                        SurfaceActions(
                            ui = ui,
                            copy = copy,
                            appLabel = appLabel,
                            nowWall = nowWall,
                            onAnswer = onChoice,
                            onLeave = leave
                        )
                    }
                }

                Spacer(Modifier.height(20.dp)) // 20 dp above the nav bar
            }
        }
    }
}

/**
 * The bottom block: one primary, at most one secondary, then the smaller options, then the door.
 *
 * Each section is guarded by its own label being non-null, which is how [InterceptCopy] decides
 * whether the engine would accept that answer. A null never turns into a disabled pill.
 */
@Composable
private fun SurfaceActions(
    ui: InterceptUi,
    copy: InterceptCopy,
    appLabel: String,
    nowWall: Long,
    onAnswer: (Choice) -> Unit,
    onLeave: () -> Unit
) {
    val primary = copy.primaryChoice(ui)
    PrimaryActionButton(
        text = copy.primaryButton(ui),
        onClick = { if (primary == Choice.Leave) onLeave() else onAnswer(primary) },
        // The star icon is a fact about this answer, not decoration: it appears only when leaving
        // right now lights one, and never on the self-test, which makes no star of its own.
        leaveEarnsStar = ui.leaveEarnsStar && primary == Choice.Leave && ui.kind != SurfaceKind.TEST
    )

    val secondaryLabel = copy.secondaryButton(ui)
    val secondary = copy.secondaryChoice(ui)
    if (secondaryLabel != null) {
        Spacer(Modifier.height(Spacing.md))
        if (secondary != null) {
            SecondaryActionButton(
                text = secondaryLabel,
                onClick = { onAnswer(secondary) },
                icon = if (secondary == Choice.Leave && ui.leaveEarnsStar) Icons.Filled.Star else null,
                iconContentDescription = if (secondary == Choice.Leave && ui.leaveEarnsStar) {
                    stringResource(R.string.intercept_leave_star_content_description)
                } else {
                    null
                }
            )
        } else {
            // PAUSE, REFLECT, PRAYER and TIME_UP: the second answer is "yes, for how long".
            StayOptionsButton(
                text = secondaryLabel,
                options = ui.stayOptionsMin,
                header = copy.forHowLong(),
                chipLabel = { minutes -> copy.stayChipLabel(minutes) },
                onSelect = { minutes -> onAnswer(Choice.Stay(minutes)) }
            )
        }
    }

    // The morning's soft yes: two minutes, held to confirm (§4.4).
    if (ui.kind == SurfaceKind.MORNING) {
        ui.stayOptionsMin.firstOrNull()?.let { minutes ->
            Spacer(Modifier.height(Spacing.md))
            HoldToConfirmButton(
                text = copy.holdForMinutes(minutes),
                onConfirm = { onAnswer(Choice.Stay(minutes)) }
            )
        }
    }

    // Free scroll is a slice the user picks. It used to spend the whole balance in one tap, which
    // is not what "use 5 minutes of it" says.
    if (ui.freeScrollOptionsMin.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        ChoiceChips(
            options = ui.freeScrollOptionsMin,
            selected = null,
            onSelect = { minutes -> onAnswer(Choice.FreeScroll(minutes)) },
            label = { minutes -> copy.freeScrollChipLabel(minutes) }
        )
    }

    val quickLabel = copy.tertiaryButton(ui)
    val quickChoice = copy.tertiaryChoice(ui)
    if (quickLabel != null && quickChoice != null) {
        Spacer(Modifier.height(Spacing.md))
        HoldToConfirmButton(
            text = quickLabel,
            onConfirm = { onAnswer(quickChoice) }
        )
    }

    copy.caption(ui = ui, nowWall = nowWall)?.let { caption ->
        Spacer(Modifier.height(Spacing.md))
        InterceptCaption(text = caption)
    }

    // "Open anyway" is the last element on the surface (§A.5): under every caption, outside the
    // button stack, and absent the moment the engine stops offering it.
    OverrideEntry(ui = ui, copy = copy, appLabel = appLabel, onArm = { onAnswer(Choice.ArmOverride) })
}

/**
 * The door as it appears on a surface that offers it.
 *
 * Three states, and the fields pick between them: the day or Bedtime caption with the red text
 * pill under it, the "back at 04:00" line when a cap is spent, and nothing at all when the engine
 * is not offering the door. No container, no emoji and no page tint — one red line, last on the
 * page (§A.5).
 */
@Composable
private fun OverrideEntry(
    ui: InterceptUi,
    copy: InterceptCopy,
    appLabel: String,
    onArm: () -> Unit
) {
    val backAt = ui.overrideBackAtWall
    if (backAt != null) {
        // A cap is spent: the surface says when the door comes back instead of drawing it (§A.6).
        Spacer(Modifier.height(Spacing.md))
        InterceptCaption(text = copy.overrideBackAt(backAt))
        return
    }
    if (ui.overrideLeft == null) return
    val caption = copy.overrideCaption(ui) ?: return

    Spacer(Modifier.height(Spacing.lg))
    InterceptCaption(text = caption)
    // 24 dp: the door is read after the caption, never pressed as part of the stack above it.
    Spacer(Modifier.height(24.dp))
    TextPillButton(
        text = copy.overrideEntryLabel(),
        onClick = onArm,
        variant = TextPillVariant.COST,
        leadingIcon = Icons.Outlined.StarBorder,
        modifier = Modifier.semantics { contentDescription = copy.overrideEntryDescription(appLabel) }
    )
}

/**
 * The confirm view's bottom: the surface's own primary where his thumb already goes, then — 12 dp
 * below it, and smaller — the hold, then the door's caption (§A.5).
 *
 * The primary is the same pill, the same slot and the same answer as on the surface underneath, so
 * the way out never moves and never shrinks. The hold is 48 dp and wrap width with a 220 dp floor:
 * it is never bigger than the way out, and never a full-width red block.
 */
@Composable
private fun OverrideOpenActions(
    ui: InterceptUi,
    copy: InterceptCopy,
    appLabel: String,
    waitState: OverrideWaitState,
    remainingMs: Long,
    pickedReason: ReflectionReason?,
    onAnswer: (Choice) -> Unit,
    onLeave: () -> Unit
) {
    val primary = copy.primaryChoice(ui)
    PrimaryActionButton(
        text = copy.primaryButton(ui),
        onClick = { if (primary == Choice.Leave) onLeave() else onAnswer(primary) },
        leaveEarnsStar = ui.leaveEarnsStar && primary == Choice.Leave && ui.kind != SurfaceKind.TEST
    )

    Spacer(Modifier.height(Spacing.md))

    HoldToConfirmButton(
        text = copy.overrideWaitLabel(waitState, remainingMs),
        // The chip is the reason the engine records, so the hold does nothing without one.
        onConfirm = { pickedReason?.let { reason -> onAnswer(Choice.Override(reason)) } },
        enabled = waitState == OverrideWaitState.READY,
        fillWidth = false,
        modifier = Modifier
            .widthIn(min = 220.dp)
            .semantics { contentDescription = copy.overrideHoldDescription(appLabel) },
        tone = HoldTone.COST
    )

    copy.overrideCaption(ui)?.let { caption ->
        Spacer(Modifier.height(Spacing.md))
        InterceptCaption(text = caption)
    }
}

/** Whether leaving this surface plays the 400 ms confirmation before the answer is sent. */
private fun playsLeaveConfirmation(kind: SurfaceKind): Boolean = kind == PAUSE || kind == REFLECT

/**
 * The wall clock, refreshed twice a minute while the surface is up.
 *
 * Every time and duration the surface states is measured from this value, so "It's 23:40" and
 * "That's 5 h 41 m from now" stay true while the question sits unanswered.
 */
@Composable
private fun rememberWallClock(kind: SurfaceKind): Long {
    var nowWall by remember(kind) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(kind) {
        while (true) {
            delay(CLOCK_TICK_MS)
            nowWall = System.currentTimeMillis()
        }
    }
    return nowWall
}

/**
 * The door's wait left, re-read every 500 ms while the confirm view is up.
 *
 * The origin is the engine's `overrideArmedElapsed` and the clock is `elapsedRealtime` — the same
 * pair the engine compares in T37 — so the screen can never show a wait shorter than the engine
 * will honour, and an activity recreated mid-wait draws the real remainder instead of restarting.
 * The text only ever changes in place: no shake, no pulse, no red flash (§A.5).
 */
@Composable
private fun rememberOverrideWait(ui: InterceptUi): Long {
    val armedAt = ui.overrideArmedElapsed
    var remainingMs by remember(armedAt) {
        mutableStateOf(overrideRemainingMs(ui, SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(armedAt, ui.overrideWaitMs) {
        if (armedAt == null) return@LaunchedEffect
        while (true) {
            remainingMs = overrideRemainingMs(ui, SystemClock.elapsedRealtime())
            if (remainingMs <= 0L) break
            delay(OVERRIDE_TICK_MS)
        }
    }
    return remainingMs
}

/** The leave confirmation's length, in milliseconds (plan §4.4). */
private const val LEAVE_CONFIRM_MS: Long = 400L

/** How often the surface re-reads the wall clock. */
private const val CLOCK_TICK_MS: Long = 30_000L

/** How often the door's countdown is re-read while the confirm view is up (§A.2). */
private const val OVERRIDE_TICK_MS: Long = 500L

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

@Preview(name = "InterceptScreen · Open anyway at Bedtime · Dark", widthDp = 360, showBackground = true)
@Composable
private fun InterceptScreenOverrideNightPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        InterceptScreen(
            ui = InterceptUi(
                kind = SurfaceKind.SLEEP_GATE,
                packageName = "com.instagram.android",
                domain = null,
                suggestion = null,
                stayOptionsMin = emptyList(),
                stayRequiresHold = false,
                resumeRemainingMs = null,
                freeScrollOptionsMin = emptyList(),
                freeScrollAvailableMs = null,
                quickNeedsLeft = 1,
                budgetRemainingMs = 0L,
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
                leaveEarnsStar = false,
                forceDark = true,
                testMode = false,
                mode = Mode.BALANCED,
                overrideLeft = 1,
                overrideWaitMs = 60_000L,
                overrideArmedElapsed = 0L,
                overrideStarsAtStake = 3
            ),
            onChoice = {},
            appLabel = "Instagram",
            appIcon = {},
            interceptCopy = InterceptCopy(LocalContext.current)
        )
    }
}

@Preview(name = "InterceptScreen · Open anyway · Day", widthDp = 360, showBackground = true)
@Composable
private fun InterceptScreenOverrideLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        InterceptScreen(
            ui = InterceptUi(
                kind = SurfaceKind.COOLDOWN,
                packageName = "com.instagram.android",
                domain = null,
                suggestion = null,
                stayOptionsMin = emptyList(),
                stayRequiresHold = false,
                resumeRemainingMs = null,
                freeScrollOptionsMin = emptyList(),
                freeScrollAvailableMs = null,
                quickNeedsLeft = 2,
                budgetRemainingMs = 0L,
                restRemainingMs = TimeUnit.MINUTES.toMillis(12),
                restEndsWall = null,
                focusRemainingMs = null,
                prayer = null,
                isJumuah = false,
                nextFajrWall = null,
                windowEndWall = null,
                reflectionOpens = null,
                reflectReason = null,
                timeUpKind = null,
                leaveEarnsStar = false,
                forceDark = false,
                testMode = false,
                mode = Mode.BALANCED,
                overrideLeft = 2,
                overrideWaitMs = 30_000L,
                overrideArmedElapsed = 0L
            ),
            onChoice = {},
            appLabel = "Instagram",
            appIcon = {},
            interceptCopy = InterceptCopy(LocalContext.current)
        )
    }
}
