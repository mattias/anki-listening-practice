package dev.lovkvist.listening.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.lovkvist.listening.R
import dev.lovkvist.listening.l10n.Strings
import javax.inject.Inject

/**
 * Publishes practice playback as a media session: a media notification with play/pause, replay,
 * skip and Crystal Clear, and control from headsets (a double tap on the play button skips, or
 * marks Crystal Clear if set that way).
 * The activity connects to it while it's open; while audio plays it runs in the foreground.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var practice: PracticeController
    @Inject lateinit var strings: Strings

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )
        val crystalClear = CommandButton.Builder(CommandButton.ICON_CHECK_CIRCLE_FILLED)
            .setDisplayName(strings(R.string.crystal_clear))
            .setSessionCommand(CRYSTAL_CLEAR)
            .build()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, practice.sessionPlayer)
            .setSessionActivity(openApp)
            .setCustomLayout(listOf(crystalClear))
            .setCallback(Callback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // The player belongs to PracticeController and lives on for the next session.
        session?.release()
        session = null
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(CRYSTAL_CLEAR).build(),
                )
                // No seek bar (it would jump between the sentence and the silent gaps). The
                // timeline must stay: Media3 shows no notification for an empty one.
                .setAvailablePlayerCommands(
                    Player.Commands.Builder()
                        .addAll(
                            Player.COMMAND_PLAY_PAUSE,
                            Player.COMMAND_GET_TIMELINE,
                            Player.COMMAND_SEEK_TO_NEXT,
                            Player.COMMAND_SEEK_TO_PREVIOUS,
                            Player.COMMAND_GET_METADATA,
                            Player.COMMAND_STOP,
                            Player.COMMAND_RELEASE,
                        )
                        .build(),
                )
                .build()

        /**
         * Notes presses from a headset (anything but our own notification), so its "next" can be told
         * from the notification's skip button. Earbuds usually send a double tap as "next" themselves;
         * wired headsets send two play/pause presses, which Media3 turns into "next". Media3 still
         * handles the press itself.
         */
        @OptIn(UnstableApi::class)
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            val fromHeadset = !session.isMediaNotificationController(controllerInfo)
            if (key?.action == KeyEvent.ACTION_DOWN && key.keyCode in HEADSET_KEYS && fromHeadset) practice.onHeadsetButton()
            return false
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CRYSTAL_CLEAR.customAction) practice.crystalClear()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        val CRYSTAL_CLEAR = SessionCommand("dev.lovkvist.listening.CRYSTAL_CLEAR", Bundle.EMPTY)
        val HEADSET_KEYS = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}
