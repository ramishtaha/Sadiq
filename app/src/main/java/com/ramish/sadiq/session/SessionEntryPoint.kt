package com.ramish.sadiq.session

import android.content.Context
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.SoundController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The way the notification receiver reaches the graph.
 *
 * `NotificationActionReceiver` is declared in the manifest, so the system instantiates it and there
 * is no constructor to inject. `@AndroidEntryPoint` would cover a receiver too, but this is the same
 * cold-process case `BootReceiver` was written for — a notification action can arrive when no
 * activity is alive — and the entry point keeps the receiver a plain object with an empty
 * constructor, which is exactly what the manifest promises.
 *
 * Only what an action needs is named here: the engine (to submit the answer), the sound player (to
 * toggle the soundscape) and the preferences (for the default sound when a silent session is given
 * one). The graph behind it is the application's singleton component, so the receiver and the UI
 * share one engine and one player.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SessionEntryPoint {

    /** The engine: every notification action is a [com.ramish.sadiq.core.engine.GuardInput]. */
    fun guardController(): GuardController

    /** The one sound player, so "Sound on/off" reaches the track that is already running. */
    fun soundController(): SoundController

    /** The preferences, for `focus_default_sound`. */
    fun settingsRepository(): SettingsRepository
}

/**
 * Fetches [SessionEntryPoint] from the application that [context] belongs to.
 *
 * `applicationContext` is used deliberately: a receiver's own context is a `ReceiverRestrictedContext`
 * and Hilt refuses anything but the application.
 *
 * @param context any context of the app.
 * @return the entry point of the singleton component.
 */
fun sessionEntryPoint(context: Context): SessionEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, SessionEntryPoint::class.java)
