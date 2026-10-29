package com.ramish.sadiq.core.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rain: pink noise band-limited to 400 Hz – 8 kHz, with sparse droplet transients on top.
 *
 * The noise bed is Kellet's pink filter followed by a 2nd-order high-pass at 400 Hz (removes the rumble that
 * would otherwise mask everything) and a 2nd-order low-pass at 8 kHz (softens the hiss). Droplets arrive as a
 * Poisson process at a density drawn once per source in 30 – 60 per second; each is a 2 – 6 ms decaying burst of
 * high-passed noise, which is what a drop hitting a surface actually sounds like.
 *
 * Level: the bed sits at about −20 dBFS RMS and the droplets add transients up to roughly 0.25 FS; the measured
 * peak over ten minutes is 0.62 FS (0.52 for a quieter seed) and [Dsp.sample] clamps at 0.9 FS as a hard ceiling.
 */
class RainSource(
    seed: Long,
    override val sampleRate: Int = Fades.SAMPLE_RATE,
) : SampleSource {

    private val random = Dsp.SeededRandom(seed)
    private val pink = PinkFilter()
    private val highPass = Biquad.highPass(HIGH_PASS_HZ, sampleRate)
    private val lowPass = Biquad.lowPass(LOW_PASS_HZ, sampleRate)

    /** Drawn once per source so a given seed always rains at the same rate, inside the specified band. */
    private val triggerChance: Double = random.nextBetween(MIN_DROPS_PER_SECOND, MAX_DROPS_PER_SECOND) / sampleRate

    /** A droplet needs a sub-millisecond attack, otherwise the burst starts with a click of its own. */
    private val attackFrames: Int = (sampleRate * DROPLET_ATTACK_SECONDS).toInt().coerceAtLeast(2)

    private val dropletEnv = DoubleArray(DROPLET_VOICES)
    private val dropletDecay = DoubleArray(DROPLET_VOICES)
    private val dropletAttackLeft = IntArray(DROPLET_VOICES)
    private val dropletAttackStep = DoubleArray(DROPLET_VOICES)

    private var previousWhite = 0.0

    override fun fill(buffer: ShortArray, frames: Int) {
        require(frames in 0..buffer.size) { "frames=$frames outside 0..${buffer.size}" }
        for (i in 0 until frames) {
            val white = random.nextBipolar()
            val bed = lowPass.process(highPass.process(pink.process(white))) * BED_LEVEL

            if (random.nextDouble() < triggerChance) triggerDroplet()

            // A first difference is a cheap high-pass: droplets are clicks, not tones.
            val edge = (white - previousWhite) * 0.5
            previousWhite = white

            var droplets = 0.0
            for (voice in 0 until DROPLET_VOICES) {
                if (dropletAttackLeft[voice] > 0) {
                    dropletEnv[voice] += dropletAttackStep[voice]
                    dropletAttackLeft[voice]--
                } else {
                    dropletEnv[voice] *= dropletDecay[voice]
                }
                droplets += dropletEnv[voice] * edge
            }

            buffer[i] = Dsp.sample(bed + droplets)
        }
    }

    /** Fills the quietest voice, so a new drop never cuts off one that is still ringing. */
    private fun triggerDroplet() {
        var quietest = 0
        for (voice in 1 until DROPLET_VOICES) {
            if (dropletEnv[voice] < dropletEnv[quietest]) quietest = voice
        }
        val tauSeconds = random.nextBetween(MIN_DROPLET_SECONDS, MAX_DROPLET_SECONDS)
        val amp = random.nextBetween(MIN_DROPLET_AMP, MAX_DROPLET_AMP)
        dropletEnv[quietest] = 0.0
        dropletAttackLeft[quietest] = attackFrames
        dropletAttackStep[quietest] = amp / attackFrames
        dropletDecay[quietest] = exp(-1.0 / (tauSeconds * sampleRate))
    }

    private companion object {
        const val HIGH_PASS_HZ = 400.0
        const val LOW_PASS_HZ = 8_000.0

        const val MIN_DROPS_PER_SECOND = 30.0
        const val MAX_DROPS_PER_SECOND = 60.0

        const val DROPLET_VOICES = 4
        const val DROPLET_ATTACK_SECONDS = 0.0003
        const val MIN_DROPLET_SECONDS = 0.002
        const val MAX_DROPLET_SECONDS = 0.006
        const val MIN_DROPLET_AMP = 0.08
        const val MAX_DROPLET_AMP = 0.22

        /** Bed gain, calibrated by measurement over ten minutes. */
        const val BED_LEVEL = 0.1
    }
}

/**
 * Paul Kellet's economical pink filter: six one-pole sections plus the direct term, giving a −3 dB/octave slope
 * with a flat enough response for a rain bed and a cost of seven multiplies per sample.
 */
private class PinkFilter {

    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var b3 = 0.0
    private var b4 = 0.0
    private var b5 = 0.0
    private var b6 = 0.0

    fun process(white: Double): Double {
        b0 = 0.99886 * b0 + white * 0.0555179
        b1 = 0.99332 * b1 + white * 0.0750759
        b2 = 0.96900 * b2 + white * 0.1538520
        b3 = 0.86650 * b3 + white * 0.3104856
        b4 = 0.55000 * b4 + white * 0.5329522
        b5 = -0.7616 * b5 - white * 0.0168980
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
        b6 = white * 0.115926
        return pink
    }
}

/** Direct-form-I biquad, RBJ cookbook coefficients with a Butterworth Q. */
private class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    companion object {
        private val Q = 1.0 / sqrt(2.0)

        fun lowPass(cutoffHz: Double, sampleRate: Int): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = (1.0 - cosW) / 2.0 / a0,
                b1 = (1.0 - cosW) / a0,
                b2 = (1.0 - cosW) / 2.0 / a0,
                a1 = -2.0 * cosW / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }

        fun highPass(cutoffHz: Double, sampleRate: Int): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = (1.0 + cosW) / 2.0 / a0,
                b1 = -(1.0 + cosW) / a0,
                b2 = (1.0 + cosW) / 2.0 / a0,
                a1 = -2.0 * cosW / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }
    }
}
