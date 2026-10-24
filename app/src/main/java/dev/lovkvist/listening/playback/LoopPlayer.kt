package dev.lovkvist.listening.playback

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
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
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val silence = silenceFile(context)

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

    /** Plays per sentence before [onPlayLimit] is called instead of repeating; 0 loops forever. */
    var playLimit: Int = 0
    var onPlayLimit: (() -> Unit)? = null

    var speed: Float = 1f
        set(value) {
            field = value
            player.playbackParameters = PlaybackParameters(value)
            refreshGap() // the gap plays at this speed too
        }

    /** Shown in the media notification (never the sentence itself: it's audio first). */
    var metadata: MediaMetadata = MediaMetadata.EMPTY

    private var clip: MediaItem? = null
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
        player.addListener(object : Player.Listener {
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
    fun load(uri: Uri, clip: LongRange?, autoPlay: Boolean) {
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
        this.clip = next
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
        if (playLimit > 0 && _repeats.value >= playLimit && !advancing) {
            advancing = true
            // Silence after the gap while the next sentence loads, instead of repeating this one.
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.addMediaItem(silenceItem(HOLD, HOLD_MS))
            onPlayLimit?.invoke()
        }
    }

    private fun onClipStarted() {
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
    private fun gap() = silenceItem(GAP, (delayMs * speed).toLong().coerceAtLeast(MIN_GAP_MS))

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
        const val CLIP = "clip"
        const val GAP = "gap"
        const val HOLD = "hold"
        const val MIN_GAP_MS = 20L
        const val HOLD_MS = 10_000L

        /** 12 s of 8 kHz mono silence as a WAV file, clipped to make gaps of any length. */
        fun silenceFile(context: Context): File {
            val file = File(context.cacheDir, "silence.wav")
            val rate = 8000
            val dataSize = rate * 2 * 12
            if (file.length() == 44L + dataSize) return file
            RandomAccessFile(file, "rw").use { f ->
                fun int(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
                fun short(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
                f.setLength(0)
                f.writeBytes("RIFF"); int(36 + dataSize); f.writeBytes("WAVE")
                f.writeBytes("fmt "); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
                f.writeBytes("data"); int(dataSize)
                f.write(ByteArray(dataSize))
            }
            return file
        }
    }
}
