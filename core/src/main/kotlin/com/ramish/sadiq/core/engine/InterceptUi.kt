package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.Suggestion
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.PrayerName

/**
 * Everything a surface needs to render itself, computed by the engine.
 *
 * The UI never reads [EngineState] to decide what to show: it renders this value, so a screen cannot
 * offer an option the engine would reject. Nullable fields are absent options — a null
 * [resumeRemainingMs] means there is no saved grant to resume, not "zero seconds left".
 *
 * @property kind which surface this is.
 * @property packageName the guarded app underneath.
 * @property domain the matched guarded domain, when a browser is the target.
 * @property suggestion the one next action the suggester chose, if any.
 * @property stayOptionsMin "Yes, I need it" chips, already filtered by budget.
 * @property stayRequiresHold whether the chosen chip must be held for [EngineRules.HOLD_TO_CONFIRM_MS].
 * @property resumeRemainingMs saved grant remaining, when a paused grant can be resumed.
 * @property freeScrollOptionsMin free-scroll chips, already filtered by the balance.
 * @property freeScrollAvailableMs today's free-scroll balance.
 * @property quickNeedsLeft one-minute grants left today.
 * @property budgetRemainingMs the guarded budget left, for the honest "n min left" line.
 * @property restRemainingMs rest left, when a rest is running.
 * @property restEndsWall when that rest ends, so the screen can state the time.
 * @property focusRemainingMs focus left, when a session is running.
 * @property prayer which prayer owns the window, for the prayer surface.
 * @property isJumuah whether this is the Friday window.
 * @property nextFajrWall tomorrow's Fajr, for the sleep-gate surface.
 * @property windowEndWall when the current window ends.
 * @property reflectionOpens guarded opens in this window, for the check-in line.
 * @property reflectReason the reason already chosen in this check-in, if any.
 * @property timeUpKind the grant kind that just expired, for the time-up surface.
 * @property leaveEarnsStar whether leaving right now will light a star; false drops the star text.
 * @property forceDark whether the surface must render dark regardless of the theme setting.
 * @property testMode whether this is a self-test.
 * @property mode the mode in force, for the surface's budget line.
 * @property overrideLeft "Open anyway" uses left today; null means the door is not offered (§A.1).
 * @property overrideWaitMs how long the door asks the user to wait before the hold (§A.2).
 * @property overrideArmedElapsed elapsed reading when the door was armed; non-null means the confirm
 *   view is showing (§A.2).
 * @property overrideStarsAtStake window stars the door would cost (3, 2 or null), stated before he holds.
 * @property overrideBackAtWall when the door comes back after a spent cap; non-null means it is spent.
 */
data class InterceptUi(
    val kind: SurfaceKind,
    val packageName: String,
    val domain: String?,
    val suggestion: Suggestion?,
    val stayOptionsMin: List<Int>,
    val stayRequiresHold: Boolean,
    val resumeRemainingMs: Long?,
    val freeScrollOptionsMin: List<Int>,
    val freeScrollAvailableMs: Long?,
    val quickNeedsLeft: Int?,
    val budgetRemainingMs: Long?,
    val restRemainingMs: Long?,
    val restEndsWall: Long?,
    val focusRemainingMs: Long?,
    val prayer: PrayerName?,
    val isJumuah: Boolean,
    val nextFajrWall: Long?,
    val windowEndWall: Long?,
    val reflectionOpens: Int?,
    val reflectReason: com.ramish.sadiq.core.insight.ReflectionReason?,
    val timeUpKind: GrantKind?,
    val leaveEarnsStar: Boolean,
    val forceDark: Boolean,
    val testMode: Boolean,
    val mode: Mode,
    val overrideLeft: Int? = null,
    val overrideWaitMs: Long? = null,
    val overrideArmedElapsed: Long? = null,
    val overrideStarsAtStake: Int? = null,
    val overrideBackAtWall: Long? = null
)
