package com.ramish.sadiq.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process's one link to the bound accessibility service.
 *
 * The service is the only component that can press Home and the only context that may add a
 * `TYPE_ACCESSIBILITY_OVERLAY` window (§3.5), and it is bound and unbound by the system at times
 * nothing else controls. Everything that needs those two powers — [HomeActionImpl],
 * [ContextInterceptLauncher] and the interception overlay — therefore asks this handle instead of
 * holding a service reference of its own, which is what keeps a stale reference from outliving an
 * unbind.
 *
 * The reference is atomic because it is written on the main thread when the system binds the
 * service and read from the engine's single input thread and from the overlay's coroutine.
 */
@Singleton
class GuardServiceHandle @Inject constructor() {

    private val service = AtomicReference<AccessibilityService?>(null)

    private val mutableConnected = MutableStateFlow(false)

    /** Whether the service is bound right now. Screens dim the sky when this is false (§3.5). */
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    /**
     * Record the bound service.
     *
     * Called from `onServiceConnected`. A second attach without a detach simply replaces the
     * reference: the system never keeps two bindings of the same service alive, and the newest one
     * is by definition the usable one.
     *
     * @param service the service that just connected.
     */
    fun attach(service: AccessibilityService) {
        this.service.set(service)
        mutableConnected.value = true
    }

    /** Forget the service. Called from `onUnbind` and `onDestroy`. */
    fun detach() {
        service.set(null)
        mutableConnected.value = false
    }

    /**
     * Press Home through the accessibility API, which works over any app and needs no activity.
     *
     * @return true when the service performed the action; false when it is not connected, in which
     *   case the caller falls back to a `CATEGORY_HOME` intent.
     */
    fun performHome(): Boolean =
        service.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false

    /** The window manager of the accessibility service, for the overlay fallback, or null when unbound. */
    fun windowManager(): WindowManager? =
        service.get()?.getSystemService(WindowManager::class.java)

    /**
     * The service itself as a context, or null when unbound.
     *
     * An `AccessibilityService` is a `Context`, and an activity started from it inherits the
     * service's ability to start from the background — which is exactly why the interception
     * activity is launched from here when it can be (§3.5).
     */
    fun serviceContext(): Context? = service.get()
}
