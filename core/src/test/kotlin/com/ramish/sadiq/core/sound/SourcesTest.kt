package com.ramish.sadiq.core.sound

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Numeric proof for the generators: no sample leaves ±0.9 FS, every source sits inside the loudness band of
 * −24 … −16 dBFS over ten minutes, output is a pure function of the seed (and therefore of nothing else), and the
 * fades are monotonic. There is no listening in here on purpose; the ears are WP-22's job.
 */
class SourcesTest {

    private val factory = DefaultSampleSourceFactory()

    // ── ten-minute acceptance run ────────────────────────────────────────────────────────────────────────────

    @Test
    fun brown_stays_inside_the_peak_and_rms_limits_for_ten_minutes() {
        assertHealthy("BROWN", analyse(factory.create(SoundKind.BROWN, 7L), TEN_MINUTES))
    }

    @Test
    fun rain_stays_inside_the_peak_and_rms_limits_for_ten_minutes() {
        assertHealthy("RAIN", analyse(factory.create(SoundKind.RAIN, 7L), TEN_MINUTES))
    }

    @Test
    fun night_pad_stays_inside_the_peak_and_rms_limits_for_ten_minutes() {
        assertHealthy("NIGHT_PAD", analyse(factory.create(SoundKind.NIGHT_PAD, 7L), TEN_MINUTES))
    }

    @Test
    fun every_source_is_loud_enough_to_hear_and_quiet_enough_to_sleep_under() {
        for (kind in AUDIBLE) {
            for (seed in longArrayOf(1L, 42L, 9_999L)) {
                val render = analyse(factory.create(kind, seed), 120.0)
                assertTrue(
                    render.rms in MIN_RMS..MAX_RMS,
                    "$kind seed=$seed rms=${render.rms} outside −24…−16 dBFS",
                )
                assertTrue(render.peak <= MAX_SAMPLE, "$kind seed=$seed peak=${render.peak} above 0.9 FS")
            }
        }
    }

    // ── determinism ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun every_source_is_identical_for_the_same_seed() {
        for (kind in AUDIBLE) {
            val first = render(factory.create(kind, 1_234L), 30.0, chunk = 64)
            val second = render(factory.create(kind, 1_234L), 30.0, chunk = 4_096)
            assertTrue(first.contentEquals(second), "$kind changed between two runs of the same seed")
        }
    }

    @Test
    fun every_source_changes_with_the_seed() {
        for (kind in AUDIBLE) {
            val first = render(factory.create(kind, 10L), 20.0)
            val second = render(factory.create(kind, 11L), 20.0)
            assertTrue(!first.contentEquals(second), "$kind ignored its seed")
        }
    }

    @Test
    fun every_source_is_independent_of_the_buffer_size() {
        for (kind in AUDIBLE) {
            val tiny = render(factory.create(kind, 77L), 10.0, chunk = 32)
            val huge = render(factory.create(kind, 77L), 10.0, chunk = 8_192)
            assertTrue(tiny.contentEquals(huge), "$kind depends on how its buffer is chunked")
        }
    }

    // ── seamlessness ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun night_pad_has_no_step_at_a_chord_change_or_at_the_cycle_wrap() {
        val source = factory.create(SoundKind.NIGHT_PAD, 3L)
        val data = render(source, 70.0, chunk = 1_024)

        val chordChange = maxStep(data, 15.0, 17.0, source.sampleRate)
        val cycleWrap = maxStep(data, 63.0, 65.0, source.sampleRate)
        val overall = maxStep(data, 0.0, 70.0, source.sampleRate)

        assertTrue(chordChange <= overall, "a chord change steps further than the loudest ordinary sample pair")
        assertTrue(cycleWrap <= overall, "the 64 s cycle wrap steps further than the loudest ordinary sample pair")
        assertTrue(overall < 0.05, "the pad has a discontinuity: largest step $overall FS")
    }

    @Test
    fun night_pad_stays_in_the_low_register() {
        // A zero-crossing count is a crude pitch proxy, but it is enough to fail if a voice ever moves above
        // the intended A-minor register: three voices around 150–330 Hz cross a few hundred times a second.
        val source = factory.create(SoundKind.NIGHT_PAD, 3L)
        val data = render(source, 10.0, chunk = 4_096)
        var crossings = 0
        for (i in 1 until data.size) {
            if ((data[i] >= 0) != (data[i - 1] >= 0)) crossings++
        }
        val perSecond = crossings / 10.0
        assertTrue(perSecond < 1_000.0, "night pad crosses zero $perSecond times a second, too high a register")
    }

    @Test
    fun brown_and_pad_never_step_further_than_a_fifth_of_full_scale() {
        for (kind in listOf(SoundKind.BROWN, SoundKind.NIGHT_PAD)) {
            val source = factory.create(kind, 5L)
            val data = render(source, 20.0)
            assertTrue(maxStep(data, 0.0, 20.0, source.sampleRate) < 0.2, "$kind has a discontinuity")
        }
    }

    // ── fades ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun fade_in_is_monotonic_and_reaches_full_level() {
        val fadeFrames = Fades.fadeFrames(Fades.SAMPLE_RATE)
        assertEquals(132_300, fadeFrames)
        assertEquals(0f, Fades.fadeInGain(0, fadeFrames))
        assertEquals(1f, Fades.fadeInGain(fadeFrames.toLong(), fadeFrames))

        var previous = 0f
        var frame = 0L
        while (frame <= fadeFrames) {
            val gain = Fades.fadeInGain(frame, fadeFrames)
            assertTrue(gain >= previous - 1e-6f, "fade-in fell at frame $frame")
            previous = gain
            frame += 331
        }
    }

    @Test
    fun fade_out_is_monotonic_and_reaches_silence() {
        val fadeFrames = Fades.fadeFrames(Fades.SAMPLE_RATE)
        assertEquals(0f, Fades.fadeOutGain(0, fadeFrames))
        assertEquals(1f, Fades.fadeOutGain(fadeFrames.toLong(), fadeFrames))

        var previous = 1f
        var remaining = fadeFrames.toLong()
        while (remaining >= 0) {
            val gain = Fades.fadeOutGain(remaining, fadeFrames)
            assertTrue(gain <= previous + 1e-6f, "fade-out rose with $remaining frames left")
            previous = gain
            remaining -= 331
        }
    }

    @Test
    fun fade_in_applied_to_a_constant_buffer_only_rises() {
        val buffer = ShortArray(1_000) { 1_000 }
        Fades.applyFadeIn(buffer, buffer.size, startFrame = 0, fadeFrames = 2_000)
        assertEquals(0, buffer[0].toInt())
        for (i in 1 until buffer.size) {
            assertTrue(buffer[i] >= buffer[i - 1], "applied fade-in fell at $i")
        }
    }

    @Test
    fun fades_write_only_the_requested_frames() {
        val buffer = ShortArray(100) { 1_000 }
        Fades.applyFadeIn(buffer, frames = 40, startFrame = 0, fadeFrames = 2_000)
        Fades.applyFadeOut(buffer, frames = 40, framesUntilEnd = 100, fadeFrames = 2_000)
        for (i in 40 until buffer.size) {
            assertEquals(1_000, buffer[i].toInt(), "fade touched sample $i beyond the requested frames")
        }
    }

    // ── factory and level guard ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun factory_builds_every_audible_kind_at_the_contract_sample_rate() {
        for (kind in AUDIBLE) {
            val source = factory.create(kind, 0L)
            assertEquals(Fades.SAMPLE_RATE, source.sampleRate, "$kind has the wrong sample rate")
            val buffer = ShortArray(Fades.SAMPLE_RATE)
            source.fill(buffer, buffer.size)
            assertTrue(buffer.any { it != 0.toShort() }, "$kind produced silence")
        }
    }

    @Test
    fun factory_refuses_none() {
        assertFailsWith<IllegalArgumentException> { factory.create(SoundKind.NONE, 1L) }
    }

    @Test
    fun fill_writes_only_the_requested_frames() {
        for (kind in AUDIBLE) {
            val source = factory.create(kind, 3L)
            val buffer = ShortArray(512) { Short.MAX_VALUE }
            source.fill(buffer, 64)
            for (i in 64 until buffer.size) {
                assertEquals(Short.MAX_VALUE, buffer[i], "$kind wrote past the requested frames")
            }
        }
    }

    @Test
    fun sample_conversion_hard_limits_and_never_turns_nan_into_noise() {
        assertEquals(MAX_SAMPLE, Dsp.sample(1_000.0).toInt())
        assertEquals(-MAX_SAMPLE, Dsp.sample(-1_000.0).toInt())
        assertEquals(0, Dsp.sample(Double.NaN).toInt())
        assertEquals(0, Dsp.sample(0.0).toInt())
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun assertHealthy(name: String, render: Render) {
        assertTrue(render.frames >= TEN_MINUTES * Fades.SAMPLE_RATE, "$name produced too few frames")
        assertTrue(render.peak <= MAX_SAMPLE, "$name peaked at ${render.peak}, above 0.9 FS")
        assertTrue(render.rms in MIN_RMS..MAX_RMS, "$name rms=${render.rms} outside −24…−16 dBFS")
        assertTrue(render.distinct > 1_000, "$name produced (nearly) a constant signal")
    }

    private fun analyse(source: SampleSource, seconds: Double, chunk: Int = 4_096): Render {
        val buffer = ShortArray(chunk)
        val total = (seconds * source.sampleRate).toLong()
        val seen = BooleanArray(1 shl 16)
        var distinct = 0
        var frames = 0L
        var sumSquares = 0.0
        var peak = 0
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE

        while (frames < total) {
            val framesNow = minOf(chunk.toLong(), total - frames).toInt()
            source.fill(buffer, framesNow)
            for (i in 0 until framesNow) {
                val value = buffer[i].toInt()
                sumSquares += value.toDouble() * value
                if (abs(value) > peak) peak = abs(value)
                if (value < min) min = value
                if (value > max) max = value
                val bucket = value + 32_768
                if (!seen[bucket]) {
                    seen[bucket] = true
                    distinct++
                }
            }
            frames += framesNow
        }
        assertTrue(min < max, "the source produced a constant signal")
        return Render(frames, sqrt(sumSquares / frames) / 32_767.0, peak, distinct)
    }

    private fun render(source: SampleSource, seconds: Double, chunk: Int = 4_096): ShortArray {
        val total = (seconds * source.sampleRate).toInt()
        val data = ShortArray(total)
        val buffer = ShortArray(chunk)
        var done = 0
        while (done < total) {
            val frames = minOf(chunk, total - done)
            source.fill(buffer, frames)
            System.arraycopy(buffer, 0, data, done, frames)
            done += frames
        }
        return data
    }

    private fun maxStep(data: ShortArray, fromSeconds: Double, toSeconds: Double, sampleRate: Int): Double {
        val from = (fromSeconds * sampleRate).toInt().coerceIn(1, data.size)
        val to = (toSeconds * sampleRate).toInt().coerceIn(from, data.size)
        var largest = 0
        for (i in from until to) {
            largest = maxOf(largest, abs(data[i] - data[i - 1]))
        }
        return largest / 32_767.0
    }

    private class Render(val frames: Long, val rms: Double, val peak: Int, val distinct: Int)

    private companion object {
        val AUDIBLE = listOf(SoundKind.RAIN, SoundKind.BROWN, SoundKind.NIGHT_PAD)
        const val TEN_MINUTES = 600.0
        const val MAX_SAMPLE = 29_490

        /** −24 dBFS and −16 dBFS, the acceptance band. */
        const val MIN_RMS = 0.0631
        const val MAX_RMS = 0.1585
    }
}
