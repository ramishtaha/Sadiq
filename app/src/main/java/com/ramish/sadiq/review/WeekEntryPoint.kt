package com.ramish.sadiq.review

import android.content.Context
import com.ramish.sadiq.contract.HistoryRepository
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.insight.WeeklyReviewBuilder
import com.ramish.sadiq.core.time.Clock
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The way [WeeklyReviewReceiver] reaches the graph.
 *
 * A manifest-declared receiver is instantiated by the system and has no injected field, and the alarm
 * can arrive when nothing else of Sadiq is alive — the same cold-process case `BootReceiver` was
 * written for. An entry point keeps the receiver a plain object with an empty constructor, exactly as
 * the manifest promises.
 *
 * Only what a review needs is named: the history, the settings it reads the week's config from, the
 * aggregator that fills `usage_days`, the pure builder, the clock, and the scheduler that re-arms it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WeekEntryPoint {

    /** The records the review is built from. */
    fun historyRepository(): HistoryRepository

    /** Guarded settings and preferences, including `weeklyNotif`. */
    fun settingsRepository(): SettingsRepository

    /** Usage-stats rows for the fourteen days a review and its comparison need. */
    fun usageDayAggregator(): UsageDayAggregator

    /** The pure weekly-review maths of §4.3 S4. */
    fun weeklyReviewBuilder(): WeeklyReviewBuilder

    /** Wall time and zone for the review window. */
    fun clock(): Clock

    /** Re-arms the next Sunday, whether or not this one managed to post. */
    fun weeklyReviewScheduler(): WeeklyReviewScheduler
}

/**
 * Fetches [WeekEntryPoint] from the application that [context] belongs to.
 *
 * `applicationContext` is used deliberately: a receiver's own context is a `ReceiverRestrictedContext`
 * and Hilt refuses anything but the application.
 *
 * @param context any context of the app.
 * @return the entry point of the singleton component.
 */
fun weekEntryPoint(context: Context): WeekEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WeekEntryPoint::class.java)
