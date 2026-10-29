package com.ramish.saduq

/**
 * Startup work registered by a feature. The application starts every initializer once, after Hilt
 * has injected it, so a feature never has to edit [SaduqApp] to be initialised.
 */
interface AppInitializer {
    fun start()
}
