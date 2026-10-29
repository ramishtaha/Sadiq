package com.ramish.sadiq.system

import android.content.Context
import com.ramish.sadiq.contract.OemSettingsLauncher
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The way a component the system constructs reaches the graph.
 *
 * A `BroadcastReceiver` declared in the manifest is instantiated by the OS, not by Hilt, and it has
 * no constructor to inject. `@AndroidEntryPoint` covers receivers too, but only for field injection
 * after the generated base class runs; a plain receiver that has to work on `BOOT_COMPLETED`, on a
 * cold process, with no activity alive, is the case the entry point exists for — the same gotcha
 * that bit the Rafiq app.
 *
 * The interface names only what such a component needs. Adding to it is cheap; the graph behind it
 * is the application's singleton component, so the receiver and the UI share one `SettingsRepository`
 * and one permission snapshot.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SystemEntryPoint {

    /** The settings, including the keep-alive preference that decides whether to start the service. */
    fun settingsRepository(): SettingsRepository

    /** The latest permission truth, for components that report it. */
    fun permissionStatusProvider(): PermissionStatusProvider

    /** The settings deep-link launcher, for cards and receivers that open a page. */
    fun oemSettingsLauncher(): OemSettingsLauncher

    /** The accessibility binding state, which the service updates and the snapshot reads. */
    fun accessibilityBinding(): AccessibilityBinding
}

/**
 * Fetches [SystemEntryPoint] from the application that [context] belongs to.
 *
 * `applicationContext` is used deliberately: a receiver's own context is a `ReceiverRestrictedContext`
 * and Hilt refuses anything but the application.
 */
fun systemEntryPoint(context: Context): SystemEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, SystemEntryPoint::class.java)
