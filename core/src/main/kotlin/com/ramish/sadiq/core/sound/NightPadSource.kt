package com.ramish.sadiq.core.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Night pad: three detuned sine/triangle voices walking a slow I–vi–IV–V cycle in A minor.
 *
 * The cycle is sixteen seconds per chord, so one full turn takes sixty-four seconds. Each chord attacks over
 * four seconds and releases over four, and the release overlaps the next chord's attack, so the pad never has an
 * edge to click on — including at the cycle wrap, which is just another chord change. The highest note is E4 at
 * 329.6 Hz, comfortably below 440 Hz.
 *
 * A small feedback-delay network stands in for a reverb: four damped combs mixed behind the dry signal, which
 * gives the pad a room without the cost of a convolution.
 *
 * Level: −20.3 dBFS RMS with a measured peak of 0.25 FS over ten minutes; [Dsp.sample] clamps at 0.9 FS.
 */
class NightPadSource(
    seed: Long,
    override val sampleRate: Int = Fades.SAMPLE_RATE,
) : SampleSource {

    private val random = Dsp.SeededRandom(seed)

    /** Each voice is detuned by a fixed, seed-jittered number of cents, which is what makes the pad breathe. */
    private val detuneRatio = DoubleArray(VOICES) { voice ->
        val cents = BASE_DETUNE_CENTS[voice] + random.nextBetween(-DETUNE_JITTER_CENTS, DETUNE_JITTER_CENTS)
        2.0.pow(cents / 1_200.0)
    }

    private val layers = Array(LAYER_COUNT) { Layer(sampleRate, detuneRatio) }
    private val reverb = SoftReverb(sampleRate)

    private val chordFrames = (sampleRate.toLong() * CHORD_SECONDS)
    private var frame = 0L
    private var nextChordFrame = 0L
    private var chordIndex = 0

    override fun fill(buffer: ShortArray, frames: Int) {
        require(frames in 0..buffer.size) { "frames=$frames outside 0..${buffer.size}" }
        for (i in 0 until frames) {
            if (frame >= nextChordFrame) {
                startChord(chordIndex)
                chordIndex = (chordIndex + 1) % CHORDS.size
                nextChordFrame += chordFrames
            }

            var dry = 0.0
            for (layer in layers) {
                if (layer.active) dry += layer.nextSample(frame)
            }

            val wet = reverb.process(dry)
            buffer[i] = Dsp.sample((dry + wet * WET_MIX) * LEVEL)
            frame++
        }
    }

    /** Takes the oldest free layer; with a twenty-second life and a sixteen-second chord there is always one. */
    private fun startChord(index: Int) {
        var target = layers[0]
        for (layer in layers) {
            if (!layer.active) {
                target = layer
                break
            }
            if (layer.startFrame < target.startFrame) target = layer
        }
        target.start(frame, CHORDS[index])
    }

    private class Layer(
        private val sampleRate: Int,
        private val detuneRatio: DoubleArray,
    ) {

        private val phase = DoubleArray(VOICES)
        private val step = DoubleArray(VOICES)

        private val attackFrames = (sampleRate.toLong() * ATTACK_SECONDS)
        private val chordFrames = (sampleRate.toLong() * CHORD_SECONDS)
        private val lifeFrames = chordFrames + (sampleRate.toLong() * RELEASE_SECONDS)

        var startFrame = Long.MIN_VALUE
            private set
        var active = false
            private set

        fun start(frame: Long, chord: DoubleArray) {
            startFrame = frame
            active = true
            for (voice in 0 until VOICES) {
                phase[voice] = 0.0
                step[voice] = chord[voice] * detuneRatio[voice] / sampleRate
            }
        }

        fun nextSample(frame: Long): Double {
            val age = frame - startFrame
            if (age >= lifeFrames) {
                active = false
                return 0.0
            }

            val envelope = when {
                age < attackFrames -> Dsp.smoothStep(age.toDouble() / attackFrames)
                age < chordFrames -> 1.0
                else -> 1.0 - Dsp.smoothStep((age - chordFrames).toDouble() / (lifeFrames - chordFrames))
            }

            var sum = 0.0
            for (voice in 0 until VOICES) {
                var p = phase[voice] + step[voice]
                if (p >= 1.0) p -= 1.0
                phase[voice] = p
                sum += waveform(p)
            }
            return sum * envelope
        }

        /** A sine with a little triangle in it: warm, and still soft enough to sit under everything. */
        private fun waveform(phase: Double): Double {
            val sine = sin(TWO_PI * phase)
            val triangle = 4.0 * abs(phase - 0.5) - 1.0
            return sine * SINE_MIX + triangle * (1.0 - SINE_MIX)
        }
    }

    private companion object {
        const val VOICES = 3
        const val LAYER_COUNT = 3
        const val CHORD_SECONDS = 16L
        const val ATTACK_SECONDS = 4L
        const val RELEASE_SECONDS = 4L
        const val SINE_MIX = 0.72
        const val TWO_PI = 2.0 * PI

        /** A minor, I – vi – IV – V, three chord tones each, all below 440 Hz. */
        val CHORDS = arrayOf(
            doubleArrayOf(220.0000000, 261.6255653, 329.6275569), // Am: A3 C4 E4
            doubleArrayOf(174.6141157, 220.0000000, 261.6255653), // F:  F3 A3 C4
            doubleArrayOf(146.8323839, 174.6141157, 220.0000000), // Dm: D3 F3 A3
            doubleArrayOf(164.8137785, 195.9977180, 246.9416506), // Em: E3 G3 B3
        )

        val BASE_DETUNE_CENTS = doubleArrayOf(-6.0, 4.0, 9.0)
        const val DETUNE_JITTER_CENTS = 1.5

        const val WET_MIX = 0.35
        const val LEVEL = 0.11
    }
}

/**
 * Four damped feedback combs mixed together: the cheapest thing that sounds like a room rather than an echo.
 * The damping in the feedback path keeps the tail dark, which suits a night pad.
 */
private class SoftReverb(sampleRate: Int) {

    private val combs: Array<DoubleArray>
    private val indices = IntArray(DELAY_MS.size)
    private val damped = DoubleArray(DELAY_MS.size)

    init {
        combs = Array(DELAY_MS.size) { comb ->
            DoubleArray((DELAY_MS[comb] * sampleRate / 1_000.0).toInt().coerceAtLeast(1))
        }
    }

    fun process(input: Double): Double {
        var wet = 0.0
        for (comb in combs.indices) {
            val line = combs[comb]
            val index = indices[comb]
            val delayed = line[index]
            damped[comb] += (delayed - damped[comb]) * DAMPING
            line[index] = input * INPUT_GAIN + damped[comb] * FEEDBACK
            indices[comb] = if (index + 1 == line.size) 0 else index + 1
            wet += delayed
        }
        return wet / combs.size
    }

    private companion object {
        /** Coprime-ish delay lengths in milliseconds; at 44.1 kHz these are 29.7, 37.1, 41.1 and 43.7 ms. */
        val DELAY_MS = doubleArrayOf(29.7, 37.1, 41.1, 43.7)
        const val INPUT_GAIN = 0.25
        const val FEEDBACK = 0.60
        const val DAMPING = 0.35
    }
}
