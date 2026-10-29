package com.ramish.sadiq.ui.ramadan

import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.testing.FakeSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * What the Ramadan card's button actually writes.
 *
 * The card's promise is exact — "Turn on Ramadan mode? Sadiq will switch to Firm." — so the test
 * asserts the two edits and their order, and that a hand correction survives the tap.
 */
class RamadanOfferTest {

    private val initial = GuardConfig(
        mode = Mode.GENTLE,
        ramadan = RamadanConfig(mode = RamadanMode.AUTO_SUGGEST, hijriOffsetDays = -1)
    )

    private val repository = FakeSettingsRepository(initialConfig = initial)

    private val offer = RamadanOffer(repository)

    @Test
    fun acceptingTurnsRamadanOnAndThenMovesToFirm() = runTest {
        offer.accept()

        assertEquals(
            listOf(
                SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = -1)),
                SettingsChange.SetMode(Mode.HARDCORE)
            ),
            repository.applied,
            "Ramadan first, then Firm, and the moon-sighting correction is carried over",
        )
    }

    @Test
    fun acceptingWhenRamadanIsAlreadyOnOnlyMovesTheMode() = runTest {
        repository.setConfig(initial.copy(ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 1)))

        offer.accept()

        assertEquals(listOf<SettingsChange>(SettingsChange.SetMode(Mode.HARDCORE)), repository.applied)
    }
}
