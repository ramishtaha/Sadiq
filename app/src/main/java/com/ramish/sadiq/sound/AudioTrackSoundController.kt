package com.ramish.sadiq.sound

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.PowerManager
import android.util.Log
import com.ramish.sadiq.contract.SoundController
import com.ramish.sadiq.core.sound.Fades
import com.ramish.sadiq.core.sound.SampleSource
import com.ramish.sadiq.core.sound.SampleSourceFactory
import com.ramish.sadiq.core.sound.SoundKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.LinkedBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays the procedural soundscapes through a streaming [AudioTrack] on a thread of its own.
 *
 * Everything about audio happens on that one thread — the track, the wake lock, the audio focus request and the
 * fade envelope — so nothing in the player can deadlock the main thread and nothing is shared but the command
 * queue and [playing]. Commands are queued rather than executed, which is what lets the UI toggle the sound while
 * the engine is emitting effects on its own dispatcher.
 *
 * Fade rules: a session fades in over three seconds and fades out over three seconds at its natural end or on
 * [stop]. A preview is two seconds with short ramps, because a three-second ramp would leave nothing audible.
 * Starting a different sound while one is playing shortens the old fade to a quarter of a second, and a one-pole
 * gain smoother makes that shortened ramp click-free.
 *
 * The audio focus request is `AUDIOFOCUS_GAIN`; a loss pauses the track without dropping the session (and without
 * touching [playing], which describes intent, not the instantaneous output), and a transient duck lowers the
 * volume instead of stopping. The partial wake lock is held only between the first and the last frame.
 */
@Singleton
class AudioTrackSoundController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: SampleSourceFactory,
) : SoundController {

    private val _playing = MutableStateFlow(SoundKind.NONE)
    override val playing: StateFlow<SoundKind> = _playing.asStateFlow()

    private val commands = LinkedBlockingQueue<Command>(COMMAND_QUEUE_CAPACITY)
    private val workerLock = Any()
    private var worker: Thread? = null

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val powerManager: PowerManager = context.getSystemService(PowerManager::class.java)

    @Volatile
    private var focusPaused = false

    @Volatile
    private var focusDucked = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusPaused = true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focusDucked = true
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusPaused = false
                focusDucked = false
            }
        }
    }

    override fun play(kind: SoundKind, durationMs: Long?) {
        if (kind == SoundKind.NONE || (durationMs != null && durationMs <= 0L)) {
            stop()
            return
        }
        enqueue(Command.Start(kind, durationMs, preview = false))
    }

    override fun stop() {
        enqueue(Command.Stop)
    }

    override fun preview(kind: SoundKind) {
        if (kind == SoundKind.NONE) {
            stop()
            return
        }
        enqueue(Command.Start(kind, PREVIEW_MS, preview = true))
    }

    /**
     * Queues [command], replacing any queued start: only the newest request matters, and the queue must never make
     * a caller wait. The worker is started lazily so a process that never plays sound never owns a thread.
     */
    private fun enqueue(command: Command) {
        startWorkerIfNeeded()
        commands.removeIf { it is Command.Start }
        commands.offer(command)
    }

    private fun startWorkerIfNeeded() {
        synchronized(workerLock) {
            if (worker != null) return
            worker = Thread(::workLoop, THREAD_NAME).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun workLoop() {
        var pending: Command.Start? = null
        while (true) {
            val command: Command = pending ?: try {
                commands.take()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            pending = null
            when (command) {
                is Command.Start -> pending = runSession(command)
                Command.Stop -> Unit
            }
        }
    }

    /**
     * Streams one soundscape until it ends, is stopped, or another start supersedes it. Returns the start that
     * superseded it, so the caller's loop can begin that one immediately.
     */
    @SuppressLint("WakelockTimeout")
    private fun runSession(request: Command.Start): Command.Start? {
        val source = factory.create(request.kind, seedFor(request.kind))
        val sampleRate = source.sampleRate
        val minimumBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) return null

        val framesPerBuffer = maxOf(minimumBuffer / BYTES_PER_FRAME, BUFFER_FRAMES)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(framesPerBuffer * BYTES_PER_FRAME)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            track.release()
            return null
        }

        focusPaused = false
        focusDucked = false

        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock.setReferenceCounted(false)

        val fadeInFrames = if (request.preview) previewFadeFrames(sampleRate) else Fades.fadeFrames(sampleRate)
        val fadeOutFrames = if (request.preview) previewFadeFrames(sampleRate) else Fades.fadeFrames(sampleRate)
        val handoverFrames = Fades.fadeFrames(sampleRate, HANDOVER_FADE_MS)
        val gainSmoothing = 1.0 - exp(-1.0 / (GAIN_SMOOTHING_SECONDS * sampleRate))

        val pcm = ShortArray(framesPerBuffer)
        var stopAt: Long? = request.durationMs?.let { it * sampleRate / 1_000L }
        var written = 0L
        var gain = 0.0
        var volume = 1f
        var superseded: Command.Start? = null

        wakeLock.acquire()
        try {
            track.play()
            _playing.value = request.kind

            while (!Thread.currentThread().isInterrupted) {
                val command = commands.poll()
                when (command) {
                    Command.Stop -> {
                        stopAt = minOf(stopAt ?: Long.MAX_VALUE, written + fadeOutFrames)
                        superseded = null
                    }

                    is Command.Start -> {
                        superseded = command
                        stopAt = minOf(stopAt ?: Long.MAX_VALUE, written + handoverFrames)
                    }

                    null -> Unit
                }

                val stop = stopAt
                if (stop != null && written >= stop) break

                if (focusPaused) {
                    // Nothing is audible, so nothing needs the CPU awake and a queued command can end the
                    // session at once instead of fading silence.
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                    if (wakeLock.isHeld) wakeLock.release()
                    if (command != null) break
                    Thread.sleep(FOCUS_POLL_MS)
                    continue
                }
                if (!wakeLock.isHeld) wakeLock.acquire()
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                val wantedVolume = if (focusDucked) DUCK_VOLUME else 1f
                if (wantedVolume != volume) {
                    track.setVolume(wantedVolume)
                    volume = wantedVolume
                }

                val framesNow = minOf((stop ?: Long.MAX_VALUE) - written, framesPerBuffer.toLong())
                    .toInt()
                    .coerceAtLeast(0)
                if (framesNow == 0) break

                source.fill(pcm, framesNow)
                for (i in 0 until framesNow) {
                    val frame = written + i
                    val fadeIn = Fades.fadeInGain(frame, fadeInFrames)
                    val fadeOut = if (stop == null) 1f else Fades.fadeOutGain(stop - frame, fadeOutFrames)
                    val target = minOf(fadeIn, fadeOut)
                    gain += (target - gain) * gainSmoothing
                    pcm[i] = (pcm[i] * gain).roundToInt().toShort()
                }

                var offset = 0
                while (offset < framesNow) {
                    val writtenNow = track.write(pcm, offset, framesNow - offset)
                    if (writtenNow <= 0) break
                    offset += writtenNow
                }
                if (offset < framesNow) break
                written += framesNow
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: IllegalStateException) {
            // A dead AudioTrack (audio server restart, route change) must not take the player's thread with it.
            Log.w(TAG, "sound session ended early", failure)
        } finally {
            runCatching { track.pause() }
            track.release()
            audioManager.abandonAudioFocusRequest(focusRequest)
            if (wakeLock.isHeld) wakeLock.release()
            _playing.value = SoundKind.NONE
        }
        return superseded
    }

    private fun previewFadeFrames(sampleRate: Int): Int = Fades.fadeFrames(sampleRate, PREVIEW_FADE_MS)

    /**
     * A stable seed per kind: the contract asks for a seed that lets a restart continue the same noise, and a
     * stable seed also makes a two-second preview sound like the twenty-five-minute session it offers.
     */
    private fun seedFor(kind: SoundKind): Long = SOUND_SEED + kind.ordinal

    private sealed interface Command {
        data class Start(val kind: SoundKind, val durationMs: Long?, val preview: Boolean) : Command
        data object Stop : Command
    }

    private companion object {
        const val THREAD_NAME = "sadiq-sound"
        const val COMMAND_QUEUE_CAPACITY = 16
        const val BYTES_PER_FRAME = 2
        const val BUFFER_FRAMES = 4_096
        const val PREVIEW_MS = 2_000L
        const val PREVIEW_FADE_MS = 150
        const val HANDOVER_FADE_MS = 250
        const val FOCUS_POLL_MS = 20L
        const val GAIN_SMOOTHING_SECONDS = 0.004
        const val DUCK_VOLUME = 0.2f
        const val WAKE_LOCK_TAG = "Sadiq:sound"
        const val TAG = "SadiqSound"
        const val SOUND_SEED = 0x5AD0_0000L

        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
