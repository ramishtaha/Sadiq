package com.ramish.sadiq.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.ramish.sadiq.contract.PermissionSnapshot
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.guard.ClockOffset
import com.ramish.sadiq.guard.GuardControllerImpl
import com.ramish.sadiq.guard.InterceptStateHolder
import com.ramish.sadiq.nav.Routes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The debug build's command surface, driven from `adb` (WP-13):
 *
 * ```
 * adb shell am broadcast -a com.ramish.sadiq.DEBUG -e cmd dump
 * adb shell am broadcast -a com.ramish.sadiq.DEBUG -e cmd guard   -e pkg com.instagram.android
 * adb shell am broadcast -a com.ramish.sadiq.DEBUG -e cmd mode    -e m HARDCORE
 * adb shell am broadcast -a com.ramish.sadiq.DEBUG -e cmd offset  -e min 90
 * adb shell am broadcast -a com.ramish.sadiq.DEBUG -e cmd reset
 * adb shell am broadcast -a com.ramish.sadiq.DEBUG -e cmd selftest -e pkg com.instagram.android
 * ```
 *
 * The receiver exists in the debug source set alone, so nothing here can ship. It reaches the
 * singleton graph through an `@EntryPoint` rather than `@AndroidEntryPoint`, because a manifest
 * receiver has no Hilt-generated base class to inherit and `BootReceiver` established the same
 * pattern for the same reason (WP-12).
 */
class DebugCommandsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val appContext = context.applicationContext
        // Every command reads or writes a DataStore or Room, which must not run on the main thread.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                handle(appContext, intent)
            } catch (failure: Throwable) {
                Log.w(TAG, "command failed: ${failure.message}", failure)
            } finally {
                pending.finish()
            }
        }
    }

    /** Dispatch one command; unknown commands are logged rather than ignored silently. */
    private suspend fun handle(context: Context, intent: Intent) {
        val entry = EntryPointAccessors.fromApplication(context, DebugCommandsEntryPoint::class.java)
        when (val command = intent.getStringExtra(EXTRA_COMMAND)?.trim()?.lowercase(Locale.ROOT)) {
            COMMAND_DUMP -> dump(entry)
            COMMAND_GUARD -> addGuardedApp(context, entry, intent)
            COMMAND_MODE -> setMode(entry, intent)
            COMMAND_OFFSET -> setOffset(entry, intent)
            COMMAND_RESET -> reset(entry)
            COMMAND_SELFTEST -> selfTest(context, entry, intent)
            COMMAND_ONBOARDED -> markOnboarded(entry)
            else -> Log.w(TAG, "unknown cmd '$command'; use dump|guard|mode|offset|reset|selftest")
        }
    }

    /**
     * Everything the Health check and a bug report need, as one `SP/Dump` line: the engine state, the
     * settings in force, the permission truth and the last surface latency.
     */
    private fun dump(entry: DebugCommandsEntryPoint) {
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
        }
        val payload = buildJsonObject {
            put("engine", json.encodeToJsonElement(EngineState.serializer(), entry.controller().state.value))
            put("config", json.encodeToJsonElement(GuardConfig.serializer(), entry.settings().config.value))
            put("permissions", permissionsJson(entry.permissions().snapshot()))
            put("lastLatencyMs", entry.holder().lastLatencyMs.value?.let(::JsonPrimitive) ?: JsonNull)
            put("clockOffsetMin", JsonPrimitive(ClockOffset.wallOffsetMin))
        }
        Log.i(TAG, json.encodeToString(JsonObject.serializer(), payload))
    }

    /**
     * Add one package to the guarded apps, labelled from the package manager when it can be read.
     *
     * This is how a device check guards an app without walking the picker, and it goes through
     * `SettingsRepository.apply` so the change lands in exactly the same place a UI change would.
     */
    private suspend fun addGuardedApp(context: Context, entry: DebugCommandsEntryPoint, intent: Intent) {
        val packageName = intent.requireExtra(EXTRA_PACKAGE) ?: return
        val config = entry.settings().config.value
        if (config.apps.any { it.packageName == packageName }) {
            Log.i(TAG, "$packageName is already guarded")
            return
        }
        val app = GuardedApp(packageName = packageName, label = labelFor(context, packageName))
        entry.settings().apply(SettingsChange.SetApps(config.apps + app))
        Log.i(TAG, "guarded $packageName (${app.label}); ${config.apps.size + 1} apps now")
    }

    /** Force a mode, so a test can reach a rest or a short budget without waiting for a settings trip. */
    private suspend fun setMode(entry: DebugCommandsEntryPoint, intent: Intent) {
        val raw = intent.requireExtra(EXTRA_MODE) ?: return
        val mode = try {
            Mode.valueOf(raw.trim().uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "unknown mode '$raw'; use ${Mode.entries.joinToString("|")}")
            return
        }
        entry.settings().apply(SettingsChange.SetMode(mode))
        Log.i(TAG, "mode=${mode.name} budget=${mode.budgetMin}min cooldown=${mode.cooldownMin}min")
    }

    /** Shift the wall clock, then wake the engine so the new windows take effect at once. */
    private fun setOffset(entry: DebugCommandsEntryPoint, intent: Intent) {
        val raw = intent.requireExtra(EXTRA_MINUTES) ?: return
        val minutes = raw.trim().toLongOrNull()
        if (minutes == null) {
            Log.w(TAG, "offset needs whole minutes, got '$raw'")
            return
        }
        ClockOffset.setMinutes(minutes)
        entry.controller().submit(GuardInput.Tick)
        Log.i(TAG, "wall clock offset=${ClockOffset.wallOffsetMin}min")
    }

    /**
     * Return to a fresh install's engine state.
     *
     * The engine has no reset input on purpose, so this goes through the controller's debug seam and
     * clears the surface holder by hand: a state replacement emits no effects, and a stale question
     * left on screen would outlive the state that asked it.
     */
    private fun reset(entry: DebugCommandsEntryPoint) {
        entry.controller().reset()
        entry.holder().reportGone()
        Log.i(TAG, "engine state reset to initial()")
    }

    /**
     * Arm the self-test, then open the target app so the real interception path runs (T33a/T33b).
     *
     * The launch waits for the arm to reach the published state: opening the app first would race
     * the input queue and produce a plain, untagged pause instead of a `TEST` surface.
     */
    private suspend fun selfTest(context: Context, entry: DebugCommandsEntryPoint, intent: Intent) {
        val packageName = intent.requireExtra(EXTRA_PACKAGE) ?: return
        val controller = entry.controller()
        controller.submit(GuardInput.SelfTest(packageName = packageName, returnRoute = Routes.HEALTH))
        val armed = withTimeoutOrNull(ARM_TIMEOUT_MS) {
            controller.state.first { it.testArm?.packageName == packageName }
        }
        if (armed == null) Log.w(TAG, "self-test arm did not reach the state; launching anyway")
        launchPackage(context, packageName)
    }

    /** Open a package's launcher activity, or say why it cannot be opened. */
    /** Marks onboarding done, for UI inspection on a test device only. */
    private suspend fun markOnboarded(entry: DebugCommandsEntryPoint) {
        entry.settings().updatePrefs { it.copy(onboardingDone = true, onboardingStep = 0) }
        Log.i(TAG, "onboarding marked done")
    }

    private fun launchPackage(context: Context, packageName: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Log.w(TAG, "no launch intent for $packageName")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(launch)
        } catch (denied: SecurityException) {
            Log.w(TAG, "launching $packageName refused: ${denied.message}")
        }
    }

    /** The launcher label, or the package name when the app cannot be queried. */
    @Suppress("DEPRECATION")
    private fun labelFor(context: Context, packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /** A [PermissionSnapshot] as JSON, field by field; the contract type is not `@Serializable`. */
    private fun permissionsJson(snapshot: PermissionSnapshot): JsonObject = buildJsonObject {
        put("accessibilityEnabled", snapshot.accessibilityEnabled)
        put("accessibilityConnected", snapshot.accessibilityConnected)
        put("notifications", snapshot.notifications)
        put("usageAccess", snapshot.usageAccess)
        put("batteryUnrestricted", snapshot.batteryUnrestricted)
        put("isXiaomiFamily", snapshot.isXiaomiFamily)
        put("hyperOsMajor", snapshot.hyperOsMajor?.let(::JsonPrimitive) ?: JsonNull)
        put("backgroundPopups", snapshot.backgroundPopups.name)
        put("autostart", snapshot.autostart.name)
        put("liveUpdates", snapshot.liveUpdates.name)
        put("sdkInt", snapshot.sdkInt)
    }

    /** The string extra [name], or null after logging what was missing. */
    private fun Intent.requireExtra(name: String): String? {
        val value = getStringExtra(name)
        if (value.isNullOrBlank()) Log.w(TAG, "missing -e $name <value>")
        return value?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** The action every debug command is sent with. */
        const val ACTION: String = "com.ramish.sadiq.DEBUG"

        /** `-e cmd <dump|guard|mode|offset|reset|selftest>`. */
        const val EXTRA_COMMAND: String = "cmd"

        /** `-e pkg <package>` for `guard` and `selftest`. */
        const val EXTRA_PACKAGE: String = "pkg"

        /** `-e m <GENTLE|BALANCED|HARDCORE>` for `mode`. */
        const val EXTRA_MODE: String = "m"

        /** `-e min <minutes>` for `offset`. */
        const val EXTRA_MINUTES: String = "min"

        private const val TAG = "SP/Dump"
        private const val COMMAND_DUMP = "dump"

        /** Debug-only: mark onboarding complete so the Sky can be inspected without a full walkthrough. */
        private const val COMMAND_ONBOARDED = "onboarded"
        private const val COMMAND_GUARD = "guard"
        private const val COMMAND_MODE = "mode"
        private const val COMMAND_OFFSET = "offset"
        private const val COMMAND_RESET = "reset"
        private const val COMMAND_SELFTEST = "selftest"

        /** How long `selftest` waits for the arm to be published before launching regardless. */
        private const val ARM_TIMEOUT_MS = 2_000L
    }
}

/**
 * The debug graph: the engine, its settings and the two objects a command reads.
 *
 * Hilt entry points are the documented way for a component that cannot be annotated
 * (`BroadcastReceiver` here) to reach the singleton graph, and they keep the receiver's constructor
 * empty, which is what the manifest requires.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugCommandsEntryPoint {
    /** The engine, so `reset` can reach its debug seam. */
    fun controller(): GuardControllerImpl

    /** The settings in force and the only write path. */
    fun settings(): SettingsRepository

    /** The permission truth, for `dump`. */
    fun permissions(): PermissionStatusProvider

    /** The published surface and the last latency, for `dump` and `reset`. */
    fun holder(): InterceptStateHolder
}
