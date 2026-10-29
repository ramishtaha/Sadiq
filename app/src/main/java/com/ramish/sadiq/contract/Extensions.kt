package com.ramish.sadiq.contract

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.Flow

/** The icon a home card shows. */
enum class HomeCardIcon {
    WARNING,
    MOON,
    CALENDAR,
    INFO
}

/**
 * One card on the Sky screen: a status Sadiq owes the user an explanation for.
 *
 * The card carries its own click, because only the provider knows which settings page or route the
 * fix lives behind.
 *
 * @property id stable identity, so the same card is not animated twice.
 * @property icon the leading icon.
 * @property title the card's one-line claim.
 * @property body the sentence under it.
 * @property actionLabel the button label, or null for a card with no action.
 * @property action the button's work.
 */
data class HomeCardModel(
    val id: String,
    val icon: HomeCardIcon,
    val title: String,
    val body: String,
    val actionLabel: String?,
    val action: () -> Unit
)

/**
 * A contributor of home cards.
 *
 * The Sky screen renders at most one card: the highest-priority provider with a non-null card. That is
 * how "Sadiq is asleep" (100) beats "Allow pop-ups" (80) without the screen knowing either feature.
 */
interface HomeCardProvider {
    /** Higher wins. The plan reserves 100 for accessibility, 80 for the pop-up fallback. */
    val priority: Int

    /** The card right now, or null when it has nothing to say. */
    val card: Flow<HomeCardModel?>
}

/** A named section that other features can add items to. */
enum class SlotId {
    RULES_SECTION,
    SETTINGS_SECTION
}

/**
 * A row or section contributed to a host screen.
 *
 * The Guard and Settings screens render every slot for their id in [order], so a feature (strict mode,
 * Ramadan, the weekly review) can appear without editing either screen.
 */
interface UiSlot {
    /** Which host screen renders it. */
    val slot: SlotId

    /** Where in that section it sits; lower first. */
    val order: Int

    /** Draw the row. @param nav the host's controller, for navigation from the row. */
    @Composable
    fun Content(nav: NavHostController)
}
