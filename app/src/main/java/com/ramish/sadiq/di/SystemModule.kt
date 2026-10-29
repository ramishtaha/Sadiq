package com.ramish.sadiq.di

import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.contract.OemSettingsLauncher
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.UsageHistorySource
import com.ramish.sadiq.system.InstalledAppsSourceImpl
import com.ramish.sadiq.system.OemSettingsLauncherImpl
import com.ramish.sadiq.system.PermissionStatusProviderImpl
import com.ramish.sadiq.system.UsageHistorySourceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * WP-12's bindings: permission truth, the OEM settings launcher, the installed-app list and usage
 * history.
 *
 * Only interfaces are bound here. `HyperOs` is an object, `MiuiAppOps`, `AccessibilityBinding` and
 * `NeverGuardResolver` are constructor-injected singletons with no alternative implementation, so
 * binding them again would only add a name nothing needs. `AppCatalog` is pure data in `core`.
 *
 * All four are `@Singleton` because each one owns state that must not fork: the permission snapshot
 * is refreshed by name from several screens, the launcher caches the app label, the app list is
 * read once per picker open, and usage history would otherwise re-open a `UsageStatsManager` query
 * per caller.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SystemModule {

    @Binds
    @Singleton
    abstract fun bindPermissionStatusProvider(
        impl: PermissionStatusProviderImpl,
    ): PermissionStatusProvider

    @Binds
    @Singleton
    abstract fun bindOemSettingsLauncher(impl: OemSettingsLauncherImpl): OemSettingsLauncher

    @Binds
    @Singleton
    abstract fun bindInstalledAppsSource(impl: InstalledAppsSourceImpl): InstalledAppsSource

    @Binds
    @Singleton
    abstract fun bindUsageHistorySource(impl: UsageHistorySourceImpl): UsageHistorySource
}
