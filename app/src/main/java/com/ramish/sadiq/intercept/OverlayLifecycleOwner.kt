package com.ramish.sadiq.intercept

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The owners a `ComposeView` expects from whatever hosts it, for a window that has no activity.
 *
 * Compose in a window added by a service needs what `ComponentActivity` normally supplies: a
 * `LifecycleOwner` (a recomposition is paused below `STARTED`, so a window whose owner never leaves
 * `INITIALIZED` draws nothing at all), a `ViewModelStoreOwner`, a `SavedStateRegistryOwner` for
 * `rememberSaveable`, and an `OnBackPressedDispatcherOwner` because `InterceptScreen` installs a
 * `BackHandler` and that composable reads the dispatcher from the composition.
 *
 * The state machine is deliberately three calls long and follows the window's life, not a screen's:
 * [create] before the view is attached, [resume] once it is, [destroy] after it is removed. All three
 * run on the main thread, which is where `LifecycleRegistry` insists on being driven.
 */
internal class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {

    private val registry = LifecycleRegistry(this)

    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /**
     * The overlay's own dispatcher.
     *
     * It is never fed by the platform — a service window gets key events, not Back callbacks, which
     * is why [OverlayRootView] handles the key itself. It exists so `BackHandler` inside
     * `InterceptScreen` has a dispatcher to register with instead of failing the composition.
     */
    override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()

    /** Restores the saved state and moves to `CREATED`. Called before the view is attached. */
    fun create() {
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    /** Moves to `RESUMED`. Compose composes and recomposes only from `STARTED` upwards. */
    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** Moves to `DESTROYED`, which disposes the composition, and releases the view-model store. */
    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
