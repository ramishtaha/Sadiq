package com.ramish.sadiq.core.sound

/** The soundscapes Sadiq can play. [NONE] means silence, and is the default everywhere. */
enum class SoundKind {
    NONE,
    RAIN,
    BROWN,
    NIGHT_PAD
}

/**
 * A generated sound source: mono, 16-bit, [sampleRate] Hz, produced sample by sample.
 *
 * Nothing is bundled and nothing is streamed, which is why the app needs no internet permission and
 * no audio assets (§3.9).
 */
interface SampleSource {
    /** Samples per second. 44 100 for every source Sadiq ships. */
    val sampleRate: Int

    /**
     * Fill [frames] samples.
     *
     * @param buffer destination; only the first `frames * 2` bytes are written.
     * @param frames how many mono frames to produce.
     */
    fun fill(buffer: ShortArray, frames: Int)
}

/** Builds a [SampleSource]. The same `(kind, seed)` must produce the same samples forever. */
interface SampleSourceFactory {
    /**
     * @param kind which soundscape.
     * @param seed the deterministic seed, so a restart continues the same noise.
     */
    fun create(kind: SoundKind, seed: Long): SampleSource
}
