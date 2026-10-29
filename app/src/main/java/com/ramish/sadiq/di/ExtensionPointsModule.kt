package com.ramish.sadiq.di

import com.ramish.sadiq.contract.ChangeGate
import com.ramish.sadiq.contract.ContentInspector
import com.ramish.sadiq.contract.HomeCardProvider
import com.ramish.sadiq.contract.UiPrefsSource
import com.ramish.sadiq.contract.UiSlot
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * The seams a feature uses to join a shared screen without editing it (§5.0 rule 3).
 *
 * The three sets are `@Multibinds`, so an empty app still injects empty sets and a feature only has to
 * contribute `@IntoSet`. The two optional bindings are what let the shell run before the features
 * that provide them exist: `MainActivity` and the data layer take `Optional<…>` and fall back.
 *
 * No work package edits this file again.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExtensionPointsModule {
    /** Cards contributed to the Sky screen. @return the set of providers. */
    @Multibinds
    abstract fun homeCardProviders(): Set<HomeCardProvider>

    /** Rows contributed to the Guard and Settings sections. @return the set of slots. */
    @Multibinds
    abstract fun uiSlots(): Set<UiSlot>

    /** Content readers contributed to the accessibility service. @return the set of inspectors. */
    @Multibinds
    abstract fun contentInspectors(): Set<ContentInspector>

    /** Absent until strict mode ships; the data layer then applies changes immediately. */
    @BindsOptionalOf
    abstract fun changeGate(): ChangeGate

    /** Absent until the data layer ships; the shell then follows the system theme. */
    @BindsOptionalOf
    abstract fun uiPrefsSource(): UiPrefsSource
}
