package com.ramish.sadiq.guard.service

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
 */
class ScreenStateReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received screen intent: $action")

        val input = when (action) {
            Intent.ACTION_SCREEN_OFF -> GuardInput.ScreenOff
            Intent.ACTION_SCREEN_ON -> GuardInput.ScreenOn
            Intent.ACTION_USER_PRESENT -> GuardInput.ScreenOn // Treat USER_PRESENT as screen on
            else -> return
        }

        scope.launch {
            systemEntryPoint(context).guardController().submit(input)
        }
    }

    companion object {
        private const val TAG = "SP/ScreenState"
    }
}
