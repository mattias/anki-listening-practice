package dev.lovkvist.listening.playback

import android.content.Context
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * Plays one clip on repeat with a configurable pause between repetitions.
 *
 * The pause is a silent item in the playlist ([clip, gap] on repeat), not a stop and restart, so
 * the player stays "playing" throughout. That keeps the media notification and its foreground
 * service alive, and lets ExoPlayer handle audio focus: it pauses while a call or another app
 * has focus and resumes after. Must be used on the main thread.
 */
class LoopPlayer(private val context: Context) {
    /**
     * The slow speeds are made by the audio output itself (AudioTrack playback parameters) and not by ExoPlayer's own Sonic. With Sonic a
     * long clip (a chain of lines) played slower than normal left the player standing still in the first pause on at least one phone.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableAudioTrackPlaybackParams(true),
    ).build()
    /**
     * The silence the pauses are made of, in the same audio format as the clip (sample rate and channels). With another format the
     * audio output is stopped and built again at every switch between a clip and its pause, and over Bluetooth that stops and
     * restarts the stream each time, which sometimes never comes back. With the same format the output just keeps playing.
     */
    private var silence = silenceFile(context, FALLBACK_RATE, 1)
    private var probedUri: Uri? = null

    private val _isPlaying = MutableStateFlow(false)
    /** True while the user wants playback, including the silent gap between repeats. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _repeats = MutableStateFlow(0)
    /** Completed plays of the current clip. */
    val repeats: StateFlow<Int> = _repeats.asStateFlow()

    var delayMs: Long = 1000
        set(value) {
            field = value
            refreshGap()
        }

    /** The gap after a play is also as long as the clip itself, to sing it back (songs). */
    var singBack: Boolean = false
        set(value) {
            field = value
            refreshGap()
        }

    /** Plays per sentence before [onPlayLimit] is called instead of repeating; 0 loops forever. */
    var playLimit: Int = 0
    var onPlayLimit: (() -> Unit)? = null

    /** Called each time a play of the clip has ended, repeats included. */
    var onPlayed: (() -> Unit)? = null

    /** Playback is going right now: a clip or the pause after it, not paused or waiting for a call to end. */
    val isRunning: Boolean get() = player.isPlaying

    /** The audio of the clip itself is what plays (not the silence between plays). */
    val isInClip: Boolean get() = player.isPlaying && player.currentMediaItem?.mediaId == CLIP

    private var speedValue = 1f
    /** A speed to start using when the next play starts (see [setSpeedForNextPlay]). */
    private var pendingSpeed: Float? = null

    var speed: Float
        get() = speedValue
        set(value) {
            speedValue = value
            pendingSpeed = null
            player.playbackParameters = PlaybackParameters(value)
            refreshGap() // the gap plays at this speed too
        }

    /**
     * The speed of the plays to come, for a pattern of speeds. It is taken into use just as the next play of the clip starts, and the
     * pause is left as it is (a few percent off in length). Changing the speed during the pause, or replacing the pause when the
     * clip starts, left the player standing still there with a long clip (a chain).
     */
    fun setSpeedForNextPlay(value: Float) {
        pendingSpeed = value.takeIf { it != speedValue }
    }

    /** Shown in the media notification (never the sentence itself: it's audio first). */
    var metadata: MediaMetadata = MediaMetadata.EMPTY

    private var clip: MediaItem? = null
    /** Length of the loaded clip in the audio's own time; 0 when it is a whole file. */
    private var clipLengthMs = 0L
    /** The gap item in the playlist is for an older delay or speed and gets replaced when possible. */
    private var gapStale = false
    /** The play limit was reached; the next [load] continues seamlessly after the current gap. */
    private var advancing = false
    /** Paused for a call (Android 12+), and resumes when it ends. */
    private var pausedForCall = false

    init {
        // Audio only: for video files, don't spend time and battery decoding pictures.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        // Pauses for calls, alarms and other apps, and when headphones are unplugged.
        player.setAudioAttributes(attributes, /* handleAudioFocus = */ true)
        player.setHandleAudioBecomingNoisy(true)
        // Keeps the processor awake while audio plays with the screen off (needs the WAKE_LOCK permission).
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "Player error: ${error.errorCodeName}", error)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                _isPlaying.value = playWhenReady
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                when (mediaItem?.mediaId) {
                    GAP -> onClipEnded()
                    CLIP -> onClipStarted()
                }
            }
        })
        watchCalls()
    }

    /**
     * Loads [uri], or just the [clip] range of it (milliseconds), and loops that. Plays only if
     * [autoPlay]; callers pass whether audio was already playing, so it never starts by surprise.
     */
    fun load(uri: Uri, clip: LongRange?, autoPlay: Boolean, audioLanguage: String? = null) {
        // A file with several audio tracks (a dub and the original) plays the one in the practiced language.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audioLanguage)
            .build()
        val item = MediaItem.Builder().setUri(uri).setMediaId(CLIP).setMediaMetadata(metadata)
        if (clip != null) {
            item.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.first)
                    .setEndPositionMs(clip.last)
                    .build(),
            )
        }
        val next = item.build()
        silence = silenceFor(uri)
        this.clip = next
        pendingSpeed = null
        clipLengthMs = clip?.let { it.last - it.first } ?: 0L
        _repeats.value = 0
        gapStale = false
        val current = player.currentMediaItem?.mediaId
        if (advancing && autoPlay && player.playWhenReady && (current == GAP || current == HOLD)) {
            // Keep the silence that's playing and queue the new sentence right after it.
            val index = player.currentMediaItemIndex
            player.removeMediaItems(index + 1, player.mediaItemCount)
            player.removeMediaItems(0, index)
            player.addMediaItems(listOf(next, gap()))
            if (current == HOLD) player.seekTo(1, 0)
            return
        }
        advancing = false
        player.setMediaItems(listOf(next, gap()))
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.playbackParameters = PlaybackParameters(speed)
        player.prepare()
        if (autoPlay) play() else pause()
    }

    fun play() {
        pausedForCall = false
        if (player.playbackState == Player.STATE_ENDED) {
            // The silence held for a next sentence that never came: loop this one again.
            advancing = false
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.removeMediaItems(2, player.mediaItemCount)
            player.seekTo(0, 0)
        }
        player.play()
    }

    fun pause() {
        pausedForCall = false
        player.pause()
    }

    fun toggle() = if (player.playWhenReady) pause() else play()

    private var watchKey = ""
    private var watchTicks = 0

    /**
     * Called about once a second. Notices that playback is meant to be going but stands still (an audio output that stopped, the
     * silence held for a next sentence that never came, a player error) and starts the sentence over. True when it did.
     */
    fun watchdog(): Boolean {
        val waiting = player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
        if (clip == null || !player.playWhenReady || pausedForCall || waiting) {
            watchKey = ""
            watchTicks = 0
            return false
        }
        val state = player.playbackState
        val key = "$state/${player.currentMediaItemIndex}/${player.currentPosition}/${_repeats.value}"
        if (key != watchKey) {
            watchKey = key
            watchTicks = 0
            return false
        }
        watchTicks++
        // Loading the first bytes of a file may take a moment; nothing else should stand still for this long.
        if (watchTicks < (if (state == Player.STATE_BUFFERING) BUFFERING_TICKS else STALL_TICKS)) return false
        Log.w(TAG, "Playback stood still (state $state, ${player.currentPosition} ms): starting the sentence over")
        watchKey = ""
        watchTicks = 0
        recover()
        return true
    }

    /** Starts the current sentence over with a fresh audio output; the number of plays so far is kept. */
    private fun recover() {
        val current = clip ?: return
        advancing = false
        pendingSpeed = null
        player.stop() // also releases the audio output, which is what a stalled one needs
        player.clearMediaItems()
        player.setMediaItems(listOf(current, gap()))
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.playbackParameters = PlaybackParameters(speedValue)
        player.prepare()
        player.play()
    }

    /** How far the current play has come, 0 to 1; 1 during the pause after it. */
    fun clipProgress(): Float {
        val item = player.currentMediaItem ?: return 0f
        if (item.mediaId != CLIP) return 1f
        val duration = player.duration
        if (duration <= 0) return 0f
        return (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
    }

    /** Plays the current sentence again from its start. */
    fun restart() {
        if (clip == null) return
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == CLIP } ?: return
        player.seekTo(index, 0)
    }

    fun stop() {
        pause()
        advancing = false
        clip = null
        player.stop()
        player.clearMediaItems()
    }

    /** A clip just finished playing: count it, and hand over to the next sentence at the limit. */
    private fun onClipEnded() {
        _repeats.value += 1
        onPlayed?.invoke()
        if (playLimit > 0 && _repeats.value >= playLimit && !advancing) {
            advancing = true
            // Silence after the gap while the next sentence loads, instead of repeating this one.
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.addMediaItem(silenceItem(HOLD, HOLD_MS))
            onPlayLimit?.invoke()
        }
    }

    private fun onClipStarted() {
        pendingSpeed?.let { next ->
            pendingSpeed = null
            speedValue = next
            player.playbackParameters = PlaybackParameters(next)
        }
        val index = player.currentMediaItemIndex
        if (index > 0) {
            // The silence before a seamlessly loaded sentence is done.
            player.removeMediaItems(0, index)
            player.repeatMode = Player.REPEAT_MODE_ALL
            advancing = false
        }
        if (gapStale) refreshGap()
    }

    /** Puts a gap with the current delay and speed into the playlist, unless it's playing now. */
    private fun refreshGap() {
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == GAP }
        gapStale = index == null || index == player.currentMediaItemIndex
        if (index != null && !gapStale) player.replaceMediaItem(index, gap())
    }

    /** The pause between repeats; it plays at the playback speed, so it's stretched to match. */
    private fun gap(): MediaItem {
        val sing = if (singBack) clipLengthMs else 0L
        return silenceItem(GAP, (sing + delayMs * speed).toLong().coerceIn(MIN_GAP_MS, SILENCE_SECONDS * 1000L))
    }

    /** The silence file in the format of the audio at [uri]; the last answer is kept, since a video's sentences share its file. */
    private fun silenceFor(uri: Uri): File {
        if (uri == probedUri) return silence
        probedUri = uri
        val (rate, channels) = audioFormatOf(uri) ?: return silenceFile(context, FALLBACK_RATE, 1)
        return silenceFile(context, rate, channels)
    }

    /** Sample rate and number of channels of the first audio track at [uri], or null if it can't be read. */
    private fun audioFormatOf(uri: Uri): Pair<Int, Int>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).asSequence().map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.let { f ->
                    val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (rate in 8000..192000 && channels in 1..2) rate to channels else null
                }
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun silenceItem(id: String, durationMs: Long) = MediaItem.Builder()
        .setUri(Uri.fromFile(silence))
        .setMediaId(id)
        .setMediaMetadata(metadata)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(durationMs).build())
        .build()

    /**
     * Pauses while the phone rings or a call (also an app call) is going on, and resumes after.
     * Audio focus covers most calls, but not e.g. a silent ringtone. Needs Android 12 for the
     * mode listener; older versions rely on audio focus alone.
     */
    private fun watchCalls() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val audio = context.getSystemService(AudioManager::class.java)
        audio.addOnModeChangedListener(ContextCompat.getMainExecutor(context)) { mode ->
            if (mode != AudioManager.MODE_NORMAL) {
                if (player.playWhenReady) {
                    player.pause()
                    pausedForCall = true
                }
            } else if (pausedForCall) {
                pausedForCall = false
                player.play()
            }
        }
    }

    private companion object {
        const val TAG = "LoopPlayer"
        const val FALLBACK_RATE = 8000
        const val STALL_TICKS = 3
        const val BUFFERING_TICKS = 10
        const val CLIP = "clip"
        const val GAP = "gap"
        const val HOLD = "hold"
        const val MIN_GAP_MS = 20L
        const val HOLD_MS = 10_000L
        const val SILENCE_SECONDS = 60

        /** A minute of silence at [rate] Hz with [channels] channels as a WAV file, clipped to make gaps of any length. */
        fun silenceFile(context: Context, rate: Int, channels: Int): File {
            val file = File(context.cacheDir, if (rate == FALLBACK_RATE && channels == 1) "silence.wav" else "silence_${rate}_$channels.wav")
            val frame = 2 * channels
            val dataSize = rate * frame * SILENCE_SECONDS
            if (file.length() == 44L + dataSize) return file
            RandomAccessFile(file, "rw").use { f ->
                fun int(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
                fun short(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
                f.setLength(0)
                f.writeBytes("RIFF"); int(36 + dataSize); f.writeBytes("WAVE")
                f.writeBytes("fmt "); int(16); short(1); short(channels); int(rate); int(rate * frame); short(frame); short(16)
                f.writeBytes("data"); int(dataSize)
                val zeros = ByteArray(64 * 1024)
                var left = dataSize
                while (left > 0) { val n = minOf(left, zeros.size); f.write(zeros, 0, n); left -= n }
            }
            return file
        }
    }
}
