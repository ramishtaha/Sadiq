package com.ramish.sadiq.testing

import android.graphics.drawable.Drawable
import com.ramish.sadiq.contract.AppInfo
import com.ramish.sadiq.contract.InstalledAppsSource

/**
 * An [InstalledAppsSource] over a fixed list.
 *
 * The picker's rows, and the labels it saves, both come from this source, so a test can hold the
 * installed set still and assert on what the ViewModel does with it.
 *
 * @param apps the launchable apps, in the order the real source would return them.
 */
class FakeInstalledAppsSource(
    private val apps: List<AppInfo> = emptyList(),
) : InstalledAppsSource {

    override suspend fun launchableApps(): List<AppInfo> = apps

    /** Null: nothing in a JVM test renders a drawable. */
    override fun icon(packageName: String): Drawable? = null
}
