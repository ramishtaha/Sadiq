package com.ramish.sadiq.data

import androidx.datastore.core.DataStore
import com.ramish.sadiq.core.economy.EconomyDay
import com.ramish.sadiq.core.engine.Budget
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.FocusState
import com.ramish.sadiq.core.engine.Grant
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.Kindle
import com.ramish.sadiq.core.engine.LedgerEntry
import com.ramish.sadiq.core.engine.Phase
import com.ramish.sadiq.core.engine.Prompt
import com.ramish.sadiq.core.engine.Rest
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.engine.TestArm
import com.ramish.sadiq.core.economy.LeaveKind
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.data.engine.EngineSnapshotCodec
import com.ramish.sadiq.data.engine.EngineSnapshotStoreImpl
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

/**
 * The snapshot document is the one persisted artifact the engine trusts; these tests pin its
 * behaviour when it is wrong: decode to `null`, never throw, never half-load.
 */
class SnapshotCodecTest {

    @Test
    fun encodeThenDecode_returnsTheSameState() {
        val state = sampleState()

        val decoded = EngineSnapshotCodec.decode(EngineSnapshotCodec.encode(state))

        assertEquals(state, decoded)
    }

    @Test
    fun garbageJson_decodesToNull() {
        assertNull(EngineSnapshotCodec.decode("{not json at all"))
        assertNull(EngineSnapshotCodec.decode("[]"))
        assertNull(EngineSnapshotCodec.decode("{\"schema\":1}"))
    }

    @Test
    fun blankDocument_decodesToNull() {
        assertNull(EngineSnapshotCodec.decode(""))
        assertNull(EngineSnapshotCodec.decode("   \n\t "))
    }

    @Test
    fun unknownEnumValue_decodesToNull() {
        val encoded = EngineSnapshotCodec.encode(sampleState())

        val tampered = encoded.replace("\"GRANTED\"", "\"TELEPORTING\"")

        assertNull(EngineSnapshotCodec.decode(tampered))
    }

    @Test
    fun unknownSchema_decodesToNull() {
        val future = sampleState().copy(schema = EngineSnapshotCodec.SCHEMA + 1)

        assertNull(EngineSnapshotCodec.decode(EngineSnapshotCodec.encode(future)))
    }

    @Test
    fun loadFromAStoreThatFails_returnsNull() = runTest {
        val store = EngineSnapshotStoreImpl(FakeStringStore(failure = IOException("disk gone")))

        assertNull(store.load())
    }

    @Test
    fun loadFromAnEmptyStore_returnsNull() = runTest {
        val store = EngineSnapshotStoreImpl(FakeStringStore())

        assertNull(store.load())
    }

    @Test
    fun saveThenLoad_returnsTheSavedState() = runTest {
        val store = EngineSnapshotStoreImpl(FakeStringStore())
        val state = sampleState()

        store.save(state)

        assertEquals(state, store.load())
    }

    private fun sampleState(): EngineState = EngineState(
        phase = Phase.GRANTED,
        foregroundPackage = "com.instagram.android",
        foregroundGuarded = true,
        screenOn = true,
        prompt = Prompt(
            kind = SurfaceKind.PAUSE,
            packageName = "com.instagram.android",
            domain = null,
            shownWall = 1_790_000_000_000L,
            reflectReason = ReflectionReason.BORED,
            testMode = false,
            returnRoute = null,
        ),
        grant = Grant(
            id = "g42",
            kind = GrantKind.STAY,
            grantedMs = 600_000L,
            consumedMs = 120_000L,
            startedWall = 1_790_000_000_000L,
            lastAccountedElapsed = 5_000L,
            heldSinceElapsed = null,
            pausedSinceElapsed = null,
            windowAtStart = WindowKind.PRAYER,
            firstPackage = "com.instagram.android",
        ),
        budget = Budget(remainingMs = 420_000L, lastGuardedUseElapsed = 5_000L),
        rest = Rest(remainingMs = 1_800_000L, lastAccountedElapsed = 5_000L),
        focus = FocusState(
            id = "f7",
            plannedMs = 1_500_000L,
            elapsedMs = 60_000L,
            lastAccountedElapsed = 5_000L,
            startedWall = 1_790_000_000_000L,
            sound = SoundKind.RAIN,
        ),
        kindles = listOf(
            Kindle(
                id = "k1",
                leave = LeaveKind.LEFT_PROMPT,
                createdElapsed = 1_000L,
                confirmAtElapsed = 16_000L,
                sourcePackage = "com.instagram.android",
                window = WindowKind.NONE,
            ),
        ),
        recentOpensWall = listOf(1_790_000_000_000L, 1_790_000_100_000L),
        lastReflectWall = 1_789_999_000_000L,
        ledger = listOf(
            LedgerEntry(
                kind = WindowKind.PRAYER,
                startWall = 1_790_000_000_000L,
                grants = 1,
                quickNeeds = 0,
                reconciledUsage = true,
            ),
        ),
        economy = EconomyDay(
            logicalDay = 20_690,
            litLeave = 2,
            litFocus = 1,
            litTotal = 3,
            lastLeaveLitWall = 1_789_999_500_000L,
            freeUsedMs = 120_000L,
            quickNeedsUsed = 1,
        ),
        testArm = TestArm(
            packageName = "com.instagram.android",
            returnRoute = "settings",
            armedElapsed = 7_000L,
        ),
        bootCount = 3,
        seq = 42L,
        lastSeenWall = 1_790_000_200_000L,
        lastSeenElapsed = 9_000L,
    )

    /** Minimal in-memory [DataStore] standing in for the file; a failure models an unreadable file. */
    private class FakeStringStore(
        initial: String = "",
        private val failure: IOException? = null,
    ) : DataStore<String> {

        private val state = MutableStateFlow(initial)

        override val data: Flow<String> = flow {
            failure?.let { throw it }
            emitAll(state)
        }

        override suspend fun updateData(transform: suspend (t: String) -> String): String {
            failure?.let { throw it }
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
