package com.ramish.sadiq

/**
 * Startup work registered by a feature. The application starts every initializer once, after Hilt
 * has injected it, so a feature never has to edit [SadiqApp] to be initialised.
 */
interface AppInitializer {
    fun start()
}
