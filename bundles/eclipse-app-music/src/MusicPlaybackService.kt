package com.example.eclipse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.IOException
import java.security.GeneralSecurityException
import org.json.JSONException
import org.videolan.libvlc.MediaPlayer

internal data class MusicPlayback(
    val song: MusicSong?, val playing: Boolean, val loading: Boolean,
    val shuffle: Boolean, val repeat: MusicRepeat,
)

class MusicPlaybackService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@MusicPlaybackService }
    private val binder = LocalBinder()
    private val queue = MusicQueue()
    private val listeners = mutableSetOf<() -> Unit>()
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private var player: VlcPlayback? = null
    private var prepared = false
    private var wantsPlay = false
    private var resumeAfterFocus = false
    private var foreground = false
    private val wake by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:MusicPlayback").apply { setReferenceCounted(false) }
    }
    private val openMusic by lazy {
        PendingIntent.getActivity(this, 0, Intent(this, MusicActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    internal val state get() = MusicPlayback(queue.current, prepared && wantsPlay,
        player != null && !prepared, queue.shuffle, queue.repeat)
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { pause() }
    }
    private val connectionsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (MediaConnections.affects(intent, queue.current)) stopPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeAfterFocus = false; pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        resumeAfterFocus = wantsPlay
                        pause(abandonFocus = false)
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.player?.setVolume(20)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        player?.player?.setVolume(100)
                        if (resumeAfterFocus) { resumeAfterFocus = false; resume() }
                    }
                }
            }, Handler(Looper.getMainLooper())).build()
        session = MediaSession(this, "Eclipse Music").apply {
            setPlaybackToLocal(attributes)
            setSessionActivity(openMusic)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onStop() = stopPlayback()
                override fun onSeekTo(pos: Long) {
                    if (prepared) { player?.player?.setTime(pos.coerceIn(0, queue.current?.duration ?: 0)); publish() }
                }
            })
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW))
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        ContextCompat.registerReceiver(this, connectionsChanged, IntentFilter(JellyfinClient.CONNECTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        publish()
    }

    override fun onBind(intent: Intent) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "previous" -> previous()
            "toggle" -> toggle()
            "next" -> next()
            "stop" -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    internal fun listen(listener: () -> Unit) { listeners.add(listener); listener() }
    internal fun unlisten(listener: () -> Unit) { listeners.remove(listener) }

    internal fun play(songs: List<MusicSong>, id: Long) {
        queue.set(songs, id)
        prepare()
    }

    internal fun toggle() {
        if (wantsPlay) pause() else resume()
    }

    private fun resume() {
        if (queue.current == null) {
            Toast.makeText(this, "Choose a song to start playing.", Toast.LENGTH_SHORT).show()
            return
        }
        if (player == null) { prepare(); return }
        if (!requestFocus()) return
        wantsPlay = true
        keepPlaying()
        if (prepared) player?.player?.play()
        publish()
    }

    private fun pause(abandonFocus: Boolean = true) {
        wantsPlay = false
        if (prepared) player?.player?.pause()
        if (wake.isHeld) wake.release()
        if (abandonFocus) {
            resumeAfterFocus = false
            audio.abandonAudioFocusRequest(focus)
        }
        publish()
    }

    internal fun next() { if (queue.next() != null) prepare() }
    internal fun previous() { if (queue.previous() != null) prepare() }
    internal fun shuffle() { queue.toggleShuffle(); publish() }
    internal fun repeatMode() {
        queue.repeat = MusicRepeat.entries[(queue.repeat.ordinal + 1) % MusicRepeat.entries.size]
        publish()
    }

    private fun requestFocus(): Boolean {
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true
        fail("Another app is using audio. Try playing again.")
        return false
    }

    private fun prepare() {
        val song = queue.current ?: return
        player?.close()
        player = null
        prepared = false
        wantsPlay = false
        if (!requestFocus()) return
        wantsPlay = true
        keepPlaying()
        try {
            val candidate = VlcPlayback(this)
            player = candidate
            candidate.setEventListener { event ->
                if (player === candidate) when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        prepared = true
                        if (!wantsPlay) candidate.player.pause()
                        publish()
                    }
                    MediaPlayer.Event.EndReached -> {
                        if (queue.next(automatic = true) != null) prepare()
                        else pause()
                    }
                    MediaPlayer.Event.EncounteredError -> fail(
                        "Cannot play ${song.title}. Check its storage/server or file format.",
                        IOException("LibVLC could not play the audio."))
                }
            }
            candidate.open(song, video = false)
            candidate.player.play()
            publish()
        } catch (e: IOException) {
            fail("Cannot open ${song.title}. Check that its storage is connected.", e)
        } catch (e: SecurityException) {
            fail("Music file access was denied. Allow music access and try again.", e)
        } catch (e: IllegalArgumentException) {
            fail("Cannot play ${song.title}. The file is not supported.", e)
        } catch (e: GeneralSecurityException) {
            fail("Reconnect Jellyfin in Settings > Connections.", e)
        } catch (e: JSONException) {
            fail("The saved Jellyfin connection is damaged. Reconnect in Settings.", e)
        } catch (e: IllegalStateException) {
            fail("The music playback engine could not start. Try opening Music again.", e)
        }
    }

    private fun keepPlaying() {
        if (!wake.isHeld) wake.acquire()
        ContextCompat.startForegroundService(this, Intent(this, MusicPlaybackService::class.java))
        foreground = true
        startForeground(NOTIFICATION, notification())
    }

    private fun publish() {
        val song = queue.current
        session.isActive = song != null && player != null
        session.setMetadata(MediaMetadata.Builder().apply {
            song?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it.title)
                putString(MediaMetadata.METADATA_KEY_ARTIST, it.artist)
                putString(MediaMetadata.METADATA_KEY_ALBUM, it.album)
                putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it.artwork.toString())
                putLong(MediaMetadata.METADATA_KEY_DURATION, it.duration)
            }
        }.build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
            .setState(when {
                state.loading -> PlaybackState.STATE_BUFFERING
                state.playing -> PlaybackState.STATE_PLAYING
                song != null -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_STOPPED
            }, if (prepared) player?.player?.time?.coerceAtLeast(0) ?: 0 else 0, if (state.playing) 1f else 0f).build())
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        MusicArtwork.update(this, state)
        listeners.toList().forEach { it() }
    }

    private fun notification(): Notification {
        fun command(action: String) = PendingIntent.getService(this, action.hashCode(),
            Intent(this, MusicPlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_music)
            .setContentTitle(queue.current?.title ?: "Music").setContentText(queue.current?.artist ?: "Eclipse")
            .setContentIntent(openMusic).setOngoing(wantsPlay).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(R.drawable.ic_music_previous, "Previous", command("previous")).build())
            .addAction(Notification.Action.Builder(if (wantsPlay) R.drawable.ic_cc_pause else R.drawable.ic_cc_play,
                if (wantsPlay) "Pause" else "Play", command("toggle")).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_music_next, "Next", command("next")).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_cc_x, "Stop", command("stop")).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun fail(message: String, error: Exception? = null) {
        Log.w("MusicPlayback", message, error)
        player?.close()
        player = null
        if (wake.isHeld) wake.release()
        prepared = false
        wantsPlay = false
        resumeAfterFocus = false
        audio.abandonAudioFocusRequest(focus)
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf() }
        publish()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun stopPlayback() {
        player?.close(); player = null
        if (wake.isHeld) wake.release()
        prepared = false; wantsPlay = false; resumeAfterFocus = false
        audio.abandonAudioFocusRequest(focus)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        publish()
        stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(noisy)
        unregisterReceiver(connectionsChanged)
        player?.close()
        if (wake.isHeld) wake.release()
        audio.abandonAudioFocusRequest(focus)
        session.release()
        MusicArtwork.clear()
        listeners.clear()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "eclipse_music"
        const val NOTIFICATION = 4201
    }
}
