package com.ramish.sadiq.data.engine

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.ramish.sadiq.contract.EngineSnapshotStore
import com.ramish.sadiq.core.engine.EngineState
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The engine's whole state as one JSON document in `files/engine_state.json` (§3.4).
 *
 * A snapshot is a cache of the reducer's last output, never a source of truth: if the document is
 * missing, truncated or written by a schema this build does not know, [load] logs and returns
 * `null`, and the controller starts from `GuardReducer.initial()`. It never throws into the guard
 * loop and never blocks interception.
 */
@Singleton
class EngineSnapshotStoreImpl @Inject constructor(
    private val dataStore: DataStore<String>,
) : EngineSnapshotStore {

    override suspend fun load(): EngineState? = try {
        EngineSnapshotCodec.decode(dataStore.data.first())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        Log.w(TAG, "engine snapshot unreadable (${io.message}); keeping the initial state", io)
        null
    } catch (broken: IllegalStateException) {
        Log.w(TAG, "engine snapshot store unusable (${broken.message}); keeping the initial state", broken)
        null
    }

    override suspend fun save(state: EngineState) {
        try {
            dataStore.updateData { EngineSnapshotCodec.encode(state) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            // A snapshot that cannot be written costs at most one restart's worth of accounting.
            // Throwing here would take down the input loop that produced it.
            Log.w(TAG, "engine snapshot not saved (${io.message})", io)
        }
    }

    companion object {
        /** File name in the app's private files directory. */
        const val FILE_NAME: String = "engine_state.json"

        private const val TAG = "SP/data"
    }
}

/**
 * JSON codec for [EngineState], kept separate from DataStore so it can be tested without a file.
 *
 * Unknown keys are ignored so an older build can still read a document written by a newer one that
 * only added fields; a document from a different schema generation is refused (`null`) because the
 * reducer's invariants are not guaranteed across it.
 */
internal object EngineSnapshotCodec {

    const val SCHEMA: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(state: EngineState): String = json.encodeToString(EngineState.serializer(), state)

    fun decode(text: String): EngineState? {
        if (text.isBlank()) return null
        val state = try {
            json.decodeFromString(EngineState.serializer(), text)
        } catch (serialization: SerializationException) {
            return null
        } catch (illegal: IllegalArgumentException) {
            return null
        }
        return if (state.schema == SCHEMA) state else null
    }
}

/**
 * DataStore serializer for the snapshot document. It is a plain UTF-8 string: the codec above owns
 * the shape, and DataStore only owns atomic replacement of the file.
 */
internal object EngineStateJsonSerializer : Serializer<String> {

    override val defaultValue: String = ""

    override suspend fun readFrom(input: InputStream): String = input.readBytes().decodeToString()

    override suspend fun writeTo(t: String, output: OutputStream) {
        output.write(t.encodeToByteArray())
    }
}
