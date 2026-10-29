package com.ramish.sadiq.guard.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.system.systemEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives screen ON/OFF and USER_PRESENT broadcasts and forwards them to the [GuardController].
 *
 * It lives in the guard.service package because it is registered by the accessibility service
 * dynamically and interacts directly with the guard controller.
 *
 * Only the unlock is a `ScreenOn` (AUDIT-2 G-07). `ACTION_SCREEN_ON` fires with the keyguard still
 * up, so mapping it to `ScreenOn` resumed a held grant behind the lock screen; it is used only when
 * there is no keyguard to wait for.
 *
 * @param onScreenOff runs before [GuardInput.ScreenOff] is submitted, so the service can forget the
 *   foreground it last reported.
 * @param onScreenOn runs before [GuardInput.ScreenOn] is submitted, so the service can re-state what
 *   is actually in front when the phone comes back.
 */
class ScreenStateReceiver(
    private val onScreenOff: () -> Unit = {},
    private val onScreenOn: () -> Unit = {},
) : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received screen intent: $action")

        when (action) {
            Intent.ACTION_SCREEN_OFF -> {
                onScreenOff()
                submit(context, GuardInput.ScreenOff)
            }

            Intent.ACTION_SCREEN_ON -> {
                // The keyguard is still up on this broadcast; the unlock has not happened yet.
                if (keyguardIsLocked(context)) return
                onScreenOn()
                submit(context, GuardInput.ScreenOn)
            }

            Intent.ACTION_USER_PRESENT -> {
                onScreenOn()
                submit(context, GuardInput.ScreenOn)
            }
        }
    }

    private fun submit(context: Context, input: GuardInput) {
        scope.launch {
            systemEntryPoint(context).guardController().submit(input)
        }
    }

    private fun keyguardIsLocked(context: Context): Boolean =
        runCatching {
            (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked
        }.getOrNull() ?: false

    companion object {
        private const val TAG = "SP/ScreenState"
    }
}
