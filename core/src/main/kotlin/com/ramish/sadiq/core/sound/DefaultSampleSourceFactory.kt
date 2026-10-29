package com.ramish.sadiq.core.sound

/**
 * Builds the generator behind a [SoundKind]. The only random input is the seed, so the same kind and seed always
 * render the same stream, on any device and at any buffer size.
 *
 * [SoundKind.NONE] deliberately has no source: silence is not a soundscape, and a "silent source" would hide a
 * caller bug. Callers stop playback instead.
 */
class DefaultSampleSourceFactory : SampleSourceFactory {

    override fun create(kind: SoundKind, seed: Long): SampleSource = when (kind) {
        SoundKind.RAIN -> RainSource(seed)
        SoundKind.BROWN -> BrownNoiseSource(seed)
        SoundKind.NIGHT_PAD -> NightPadSource(seed)
        SoundKind.NONE -> throw IllegalArgumentException("SoundKind.NONE has no source; stop playback instead")
    }
}
