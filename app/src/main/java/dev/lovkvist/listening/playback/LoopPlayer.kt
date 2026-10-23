package dev.lovkvist.listening.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plays one clip on repeat with a configurable pause between repetitions.
 * Media3's REPEAT_MODE_ONE has no gap support, so we wait for STATE_ENDED and restart ourselves.
 */
class LoopPlayer(context: Context, private val scope: CoroutineScope) {
    private val player = ExoPlayer.Builder(context).build()
    private var gapJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    /** True while the user wants playback, including the silent gap between repeats. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _repeats = MutableStateFlow(0)
    val repeats: StateFlow<Int> = _repeats.asStateFlow()

    var delayMs: Long = 1000
    var speed: Float = 1f
        set(value) {
            field = value
            player.playbackParameters = PlaybackParameters(value)
        }

    init {
        // Audio only: for video files, don't spend time and battery decoding pictures.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && _isPlaying.value) scheduleRepeat()
            }
        })
    }

    /** Loads [uri], or just the [clip] range of it (milliseconds), and loops that. */
    fun load(uri: Uri, clip: LongRange? = null, autoPlay: Boolean = true) {
        gapJob?.cancel()
        _repeats.value = 0
        val item = MediaItem.Builder().setUri(uri)
        if (clip != null) {
            item.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.first)
                    .setEndPositionMs(clip.last)
                    .build(),
            )
        }
        player.setMediaItem(item.build())
        player.playbackParameters = PlaybackParameters(speed)
        player.prepare()
        if (autoPlay) play() else pause()
    }

    fun play() {
        _isPlaying.value = true
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun pause() {
        _isPlaying.value = false
        gapJob?.cancel()
        player.pause()
    }

    fun toggle() = if (_isPlaying.value) pause() else play()

    fun stop() {
        pause()
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        gapJob?.cancel()
        player.release()
    }

    private fun scheduleRepeat() {
        gapJob?.cancel()
        gapJob = scope.launch {
            _repeats.value += 1
            delay(delayMs)
            if (_isPlaying.value) {
                player.seekTo(0)
                player.play()
            }
        }
    }
}
