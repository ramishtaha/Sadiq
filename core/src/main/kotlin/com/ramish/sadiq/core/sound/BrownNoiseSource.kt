package com.ramish.sadiq.core.sound

/**
 * Brown noise: white noise through a leaky integrator, then a gentle one-pole low-pass.
 *
 * The leak (rather than a pure integrator) keeps the very low end from wandering away and bounds the variance,
 * so the level stays put over a twenty-minute session. The low-pass at 1.2 kHz takes the fizz off the top and
 * leaves the soft rumble that makes brown noise easy to sit inside.
 *
 * Level: [LEVEL] is calibrated for an RMS of −20.0 dBFS and a measured peak of 0.47 FS over ten minutes;
 * [Dsp.sample] still clamps at 0.9 FS as a hard ceiling.
 */
class BrownNoiseSource(
    seed: Long,
    override val sampleRate: Int = Fades.SAMPLE_RATE,
) : SampleSource {

    private val random = Dsp.SeededRandom(seed)
    private val lowPassCoefficient = Dsp.onePoleCoefficient(LOW_PASS_HZ, sampleRate)

    private var integrator = 0.0
    private var lowPass = 0.0

    override fun fill(buffer: ShortArray, frames: Int) {
        require(frames in 0..buffer.size) { "frames=$frames outside 0..${buffer.size}" }
        for (i in 0 until frames) {
            val white = random.nextBipolar()
            integrator = integrator * LEAK + white
            lowPass += (integrator - lowPass) * lowPassCoefficient
            buffer[i] = Dsp.sample(lowPass * LEVEL)
        }
    }

    private companion object {
        /** One-pole leak; a corner of roughly 3.5 Hz at 44.1 kHz. */
        const val LEAK = 0.9995

        /** Gentle low-pass that removes the top-end hiss of the integrated noise. */
        const val LOW_PASS_HZ = 1_200.0

        /** Output gain, calibrated by measurement over ten minutes. */
        const val LEVEL = 0.0055
    }
}
