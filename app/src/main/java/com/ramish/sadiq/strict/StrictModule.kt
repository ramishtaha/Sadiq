package com.ramish.sadiq.strict

import com.ramish.sadiq.AppInitializer
import com.ramish.sadiq.contract.ChangeGate
import com.ramish.sadiq.contract.HomeCardProvider
import com.ramish.sadiq.contract.UiSlot
import com.ramish.sadiq.core.strict.DefaultStrictPolicy
import com.ramish.sadiq.core.strict.StrictPolicy
import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.ui.ramadan.RamadanFeatureEntry
import com.ramish.sadiq.ui.ramadan.RamadanHomeCardProvider
import com.ramish.sadiq.ui.ramadan.RamadanSettingsSlot
import com.ramish.sadiq.ui.strict.PendingChangesSlot
import com.ramish.sadiq.ui.strict.StrictFeatureEntry
import com.ramish.sadiq.ui.strict.StrictSettingsSlot
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * "Think it over" and Ramadan mode: the policy, the gate, the applier, and the four UI contributions.
 *
 * Everything joins the shared screens through the seams WP-01 declared, so no other package has to
 * change: the [ChangeGate] is the optional binding the data layer already asks for, the two [UiSlot]s
 * are what the Guard and Settings screens render, the [HomeCardProvider] is the Ramadan card on the
 * Sky screen, and the [AppInitializer] is how the applier starts with the process.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StrictModule {

    /** Turns the data layer's optional gate on. `SettingsRepositoryImpl` then defers loosening edits. */
    @Binds
    abstract fun bindChangeGate(impl: ChangeGateImpl): ChangeGate

    /** Starts the due-change queue with the process. */
    @Binds
    @IntoSet
    abstract fun bindPendingChangeApplier(impl: PendingChangeApplier): AppInitializer

    /** "Think it over" in the Guard tab's rules section. */
    @Binds
    @IntoSet
    abstract fun bindStrictSettingsSlot(impl: StrictSettingsSlot): UiSlot

    /** "Takes effect at 23:52 · Cancel" under the strict section. */
    @Binds
    @IntoSet
    abstract fun bindPendingChangesSlot(impl: PendingChangesSlot): UiSlot

    /** The full-screen version of the section, for a deep link and the pending rows' detail. */
    @Binds
    @IntoSet
    abstract fun bindStrictFeatureEntry(entry: StrictFeatureEntry): FeatureEntry

    /** "Ramadan starts soon. Turn on Ramadan mode? Sadiq will switch to Firm." */
    @Binds
    @IntoSet
    abstract fun bindRamadanHomeCard(impl: RamadanHomeCardProvider): HomeCardProvider

    /** Ramadan mode, in the Settings screen's section area. */
    @Binds
    @IntoSet
    abstract fun bindRamadanSettingsSlot(impl: RamadanSettingsSlot): UiSlot

    /** The Ramadan screen. */
    @Binds
    @IntoSet
    abstract fun bindRamadanFeatureEntry(entry: RamadanFeatureEntry): FeatureEntry
}

/** The classification table and the wait are pure, so the policy is one stateless instance. */
@Module
@InstallIn(SingletonComponent::class)
object StrictPolicyModule {

    @Provides
    @Singleton
    fun strictPolicy(): StrictPolicy = DefaultStrictPolicy()
}
