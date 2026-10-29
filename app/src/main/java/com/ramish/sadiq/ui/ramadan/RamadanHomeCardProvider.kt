package com.ramish.sadiq.ui.ramadan

import android.content.Context
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.HomeCardIcon
import com.ramish.sadiq.contract.HomeCardModel
import com.ramish.sadiq.contract.HomeCardProvider
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.schedule.RamadanCalendar
import com.ramish.sadiq.core.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The Ramadan offer, on the Sky screen, priority 60.
 *
 * It appears on 29 Sha'ban — after the user's Hijri correction — and only while the mode is
 * [RamadanMode.AUTO_SUGGEST]. Once the card is answered the mode is no longer `AUTO_SUGGEST`, so the
 * offer can never return that year: Sadiq suggests Ramadan, it never announces it.
 *
 * Saying yes is one tap that does two things, both of them tightenings, so both land at once:
 * Ramadan mode goes on, and the mode becomes Firm. The card says so in as many words ("Sadiq will
 * switch to Firm"), because a schedule change the user did not read is a change he will resent at
 * 4 a.m.
 *
 * [priority] sits below the accessibility card (100) and the pop-up fallback (80): a phone that
 * cannot intercept at all has a more urgent thing to say than a calendar.
 *
 * The card is a [Flow] built on a five-minute ticker. A date check that only ran when the settings
 * changed would miss midnight, which is exactly when 29 Sha'ban begins.
 */
@Singleton
class RamadanHomeCardProvider @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val offer: RamadanOffer,
    private val clock: Clock,
    @ApplicationContext private val context: Context
) : HomeCardProvider {

    override val priority: Int = 60

    /**
     * A process-lifetime scope for the card's one action.
     *
     * `HomeCardModel.action` is a plain `() -> Unit`, and the two writes it makes are suspending. The
     * provider is a singleton, so this scope lives exactly as long as the app; the writes are
     * idempotent and the gate (not this class) decides whether either one waits.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val card: Flow<HomeCardModel?> = combine(
        settingsRepository.config,
        ticks()
    ) { config, _ ->
        val now = clock.now()
        if (!RamadanCalendar.shouldSuggest(now.wallMs, now.zoneId, config.ramadan)) {
            null
        } else {
            HomeCardModel(
                id = CARD_ID,
                icon = HomeCardIcon.MOON,
                title = context.getString(R.string.ramadan_card_title),
                body = context.getString(R.string.ramadan_card_body),
                actionLabel = context.getString(R.string.ramadan_card_action),
                action = { scope.launch { offer.accept() } }
            )
        }
    }

    /** Emits once immediately and then every [TICK_MS], so the card can appear at local midnight. */
    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    companion object {
        /** Stable identity, so the Sky screen does not animate the same card twice. */
        const val CARD_ID: String = "ramadan"

        /** A whole Hijri day is far longer than five minutes; this only has to catch midnight. */
        const val TICK_MS: Long = 5 * 60_000L
    }
}
