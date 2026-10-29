package com.ramish.sadiq.testing

import com.ramish.sadiq.contract.SoundController
import com.ramish.sadiq.core.sound.SoundKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [SoundController] that records requests instead of playing audio.
 *
 * [playing] follows [play] and [stop] the way the real controller's flow does, so a ViewModel that
 * renders "playing" can be tested without an `AudioTrack`.
 */
class FakeSoundController : SoundController {

    private val mutablePlaying = MutableStateFlow(SoundKind.NONE)

    override val playing: StateFlow<SoundKind> = mutablePlaying.asStateFlow()

    /** Every play request, in order. */
    val played: MutableList<Pair<SoundKind, Long?>> = mutableListOf()

    /** Every preview request, in order. */
    val previewed: MutableList<SoundKind> = mutableListOf()

    /** How many times [stop] was called. */
    var stopCount: Int = 0
        private set

    override fun play(kind: SoundKind, durationMs: Long?) {
        played += kind to durationMs
        mutablePlaying.value = kind
    }

    override fun stop() {
        stopCount += 1
        mutablePlaying.value = SoundKind.NONE
    }

    override fun preview(kind: SoundKind) {
        previewed += kind
    }
}
