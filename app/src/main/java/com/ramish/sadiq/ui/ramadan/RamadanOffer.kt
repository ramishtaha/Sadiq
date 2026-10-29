package com.ramish.sadiq.ui.ramadan

import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.strict.SettingsChange
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accepting the Ramadan offer: the two edits the card's button promises.
 *
 * Kept apart from the card so the decision is testable without an Android `Context`, and so the card
 * keeps one job — deciding whether to appear.
 *
 * The edits are deliberately separate [SettingsChange]s. Each is classified by "Think it over" in its
 * own right; both are tightenings, so both land at once, and neither can drag the other into a
 * deferral. Ramadan is written first, so the month's thirty-minute wait is already in force for
 * anything that follows.
 *
 * The Hijri correction is carried over: accepting the card must not silently undo a moon-sighting
 * adjustment the user made by hand.
 */
@Singleton
class RamadanOffer @Inject constructor(
    private val settingsRepository: SettingsRepository
) {

    /** Turn Ramadan mode on and move the mode to Firm. Both are tightenings. */
    suspend fun accept() {
        val ramadan = settingsRepository.config.value.ramadan
        if (ramadan.mode != RamadanMode.ON) {
            settingsRepository.apply(SettingsChange.SetRamadan(ramadan.copy(mode = RamadanMode.ON)))
        }
        settingsRepository.apply(SettingsChange.SetMode(Mode.HARDCORE))
    }
}
