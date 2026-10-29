package com.ramish.sadiq.di

import com.ramish.sadiq.contract.SoundController
import com.ramish.sadiq.core.sound.DefaultSampleSourceFactory
import com.ramish.sadiq.core.sound.SampleSourceFactory
import com.ramish.sadiq.sound.AudioTrackSoundController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the sound player and its generator factory. One player for the process: it owns the audio thread, the
 * audio focus request and the wake lock, so a second instance would fight the first for all three.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SoundModule {

    @Binds
    @Singleton
    abstract fun soundController(controller: AudioTrackSoundController): SoundController

    companion object {
        /** The factory is pure Kotlin in `:core` and has no dependencies, so it needs no injection itself. */
        @Provides
        @Singleton
        fun sampleSourceFactory(): SampleSourceFactory = DefaultSampleSourceFactory()
    }
}
