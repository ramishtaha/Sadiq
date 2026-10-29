package com.ramish.sadiq.core.sound

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Fade curves and level maths shared by every generated soundscape.
 *
 * The generators stream at a steady level and stay seamless across [SampleSource.fill] calls; the fades belong
 * to the player, because only the player knows when a session starts and ends. Every curve here is a
 * smoothstep: monotonic, flat at both ends, so a sound that starts or stops on it cannot click.
 */
object Fades {

    /** The fade-in and fade-out length of every focus soundscape. */
    const val DEFAULT_FADE_MS: Int = 3_000

    /** Mono 16-bit sample rate the soundscapes are rendered at. */
    const val SAMPLE_RATE: Int = 44_100

    /** Frames a [fadeMs] fade lasts at [sampleRate]. Never returns zero. */
    fun fadeFrames(sampleRate: Int, fadeMs: Int = DEFAULT_FADE_MS): Int =
        (sampleRate.toLong() * fadeMs.toLong() / 1_000L).toInt().coerceAtLeast(1)

    /** Gain [frame] frames into a fade-in of [fadeFrames]: 0 at the start, 1 once the fade is over. */
    fun fadeInGain(frame: Long, fadeFrames: Int): Float {
        if (frame <= 0L) return 0f
        if (fadeFrames <= 0) return 1f
        if (frame >= fadeFrames) return 1f
        return Dsp.smoothStep(frame.toDouble() / fadeFrames).toFloat()
    }

    /** Gain when [remainingFrames] frames are left of a fade-out of [fadeFrames]: 0 at the end, 1 before it. */
    fun fadeOutGain(remainingFrames: Long, fadeFrames: Int): Float {
        if (remainingFrames <= 0L) return 0f
        if (fadeFrames <= 0) return 1f
        if (remainingFrames >= fadeFrames) return 1f
        return Dsp.smoothStep(remainingFrames.toDouble() / fadeFrames).toFloat()
    }

    /** Scales `buffer[0 until frames]` by the fade-in that starts at absolute frame [startFrame]. */
    fun applyFadeIn(buffer: ShortArray, frames: Int, startFrame: Long, fadeFrames: Int) {
        require(frames in 0..buffer.size) { "frames=$frames outside 0..${buffer.size}" }
        for (i in 0 until frames) {
            buffer[i] = scale(buffer[i], fadeInGain(startFrame + i, fadeFrames))
        }
    }

    /**
     * Scales `buffer[0 until frames]` by the fade-out that ends [framesUntilEnd] frames after this buffer.
     * The last sample of the buffer is the sample at `framesUntilEnd` frames from the end.
     */
    fun applyFadeOut(buffer: ShortArray, frames: Int, framesUntilEnd: Long, fadeFrames: Int) {
        require(frames in 0..buffer.size) { "frames=$frames outside 0..${buffer.size}" }
        for (i in 0 until frames) {
            val remaining = framesUntilEnd + (frames - 1 - i)
            buffer[i] = scale(buffer[i], fadeOutGain(remaining, fadeFrames))
        }
    }

    private fun scale(sample: Short, gain: Float): Short =
        (sample.toDouble() * gain).roundToInt().toShort()
}

/**
 * Numeric helpers shared by the five sound files: one sample conversion with a hard 0.9 FS ceiling, one
 * one-pole coefficient helper, and a small deterministic generator. They live here rather than in a sixth file
 * because every generator needs exactly these three and nothing else.
 */
internal object Dsp {

    /** Full-scale value of a signed 16-bit sample. */
    const val FULL_SCALE: Double = 32_767.0

    /** The hard ceiling every generator is mixed against: 0.9 of full scale. */
    const val PEAK: Double = 0.9

    private const val TWO_POW_MINUS_53: Double = 1.0 / 9_007_199_254_740_992.0

    /**
     * Converts a float sample to 16-bit, refusing to leave ±[PEAK]. A NaN is mapped to silence rather than to an
     * arbitrary sample, so a numeric fault can never become a loud pop.
     */
    fun sample(value: Double): Short {
        if (value.isNaN()) return 0
        val clamped = if (value > PEAK) PEAK else if (value < -PEAK) -PEAK else value
        return (clamped * FULL_SCALE).roundToInt().toShort()
    }

    /** One-pole low-pass coefficient for a [cutoffHz] corner. */
    fun onePoleCoefficient(cutoffHz: Double, sampleRate: Int): Double =
        1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)

    /** Classic smoothstep: 0 → 1, monotonic, with zero slope at both ends. */
    fun smoothStep(t: Double): Double {
        val x = if (t < 0.0) 0.0 else if (t > 1.0) 1.0 else t
        return x * x * (3.0 - 2.0 * x)
    }

    /**
     * xorshift64* seeded by the caller. Pure 64-bit integer arithmetic, so the same seed gives the same stream
     * on every JVM and in every process; the output is independent of how a caller chunks its buffers.
     */
    class SeededRandom(seed: Long) {

        private var state: Long = if (seed == 0L) DEFAULT_SEED else seed

        fun nextLong(): Long {
            var x = state
            x = x xor (x ushr 12)
            x = x xor (x shl 25)
            x = x xor (x ushr 27)
            state = x
            return x * MULTIPLIER
        }

        /** Uniform in [0, 1) with 53 bits of resolution. */
        fun nextDouble(): Double = (nextLong() ushr 11) * TWO_POW_MINUS_53

        /** Uniform in [-1, 1). */
        fun nextBipolar(): Double = nextDouble() * 2.0 - 1.0

        fun nextBetween(min: Double, max: Double): Double = min + nextDouble() * (max - min)

        private companion object {
            const val MULTIPLIER: Long = 2_685_821_657_736_338_717L
            const val DEFAULT_SEED: Long = 2_685_821_657_736_338_717L
        }
    }
}
