package com.ramish.sadiq.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.ramish.sadiq.contract.MediaDucker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lowers other apps' audio while a question is on screen (§3.5).
 *
 * `AUDIOFOCUS_GAIN_TRANSIENT` is what makes a reel pause or duck instead of competing with the
 * pause surface; the plan names it explicitly, and it is also the mildest request that produces the
 * effect. Focus is asked for once per surface and given back when the surface goes, so a
 * `DuckMedia` that arrives twice (two transitions in a row) does not stack requests, and a
 * `ReleaseMedia` with nothing ducked is a no-op.
 *
 * `duck` and `release` are called from the effect router's coroutine while the state they belong to
 * is published from the engine's thread, so the pair is synchronized: the two calls must stay
 * balanced, and an interleaving that granted focus twice would leave the user's music ducked after
 * the question was answered.
 */
@Singleton
class MediaDuckerImpl @Inject constructor(
    @ApplicationContext context: Context,
) : MediaDucker {

    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        // A duck is enough: the other app keeps its position, which is what makes "come back to
        // this reel in a minute" true.
        .setWillPauseWhenDucked(false)
        .build()

    private var ducked: Boolean = false

    @Synchronized
    override fun duck() {
        if (ducked) return
        val manager = audio ?: return
        // The result is deliberately ignored: a denied request still has to be released later, and
        // a device without audio focus (a TV build, an emulator) should not break the surface.
        manager.requestAudioFocus(request)
        ducked = true
    }

    @Synchronized
    override fun release() {
        if (!ducked) return
        ducked = false
        audio?.abandonAudioFocusRequest(request)
    }
}
