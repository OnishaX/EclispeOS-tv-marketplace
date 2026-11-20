package com.example.eclipse

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.SQLException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONException
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.Executors

open class MusicActivity : AppCompatActivity() {
    internal open val videoKind: VideoKind? get() = null
    private val appTitle get() = videoKind?.title ?: "Music"
    private val appAccent get() = videoKind?.accent ?: 0xFFEC31BD.toInt()
    private lateinit var music: MusicView
    private lateinit var particles: ParticleBackgroundView
    private lateinit var content: FrameLayout
    private lateinit var appSettings: ItemSettingsView
    private val library by lazy { MusicLibrary(this) }
    private val remoteMapping by lazy { RemoteButtonMapping(this) }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var service: MusicPlaybackService? = null
    private var bound = false
    private var active = false
    private var observing = false
    private var stopListening: (() -> Unit)? = null
    private var shownSongs: List<MusicSong>? = null
    private var artworkGeneration = 0
    private var playlists = emptyList<MusicPlaylist>()
    private var dialog: AlertDialog? = null
    private val requestedArt = mutableSetOf<Long>()
    private val permission get() = mediaPermission(videoKind)
    private val refresh = Runnable { load() }
    private val connectionsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            main.removeCallbacks(refresh)
            main.post(refresh)
        }
    }
    private val playbackChanged: () -> Unit = { service?.let { music.playback = it.state } }
    private val videoPlaybackChanged: () -> Unit = {
        music.playback = if (VideoPlayback.kind == videoKind) VideoPlayback.state
            else MusicPlayback(null, false, false, false, MusicRepeat.NONE)
    }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(refresh)
            main.postDelayed(refresh, 400)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val playback = (binder as MusicPlaybackService.LocalBinder).service
            service = playback
            playback.listen(playbackChanged)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            music.playback = MusicPlayback(null, false, false, false, MusicRepeat.NONE)
            report("Music playback was interrupted.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        particles = ParticleBackgroundView(this).apply {
            musicArtworkBackground = videoKind == null
            glowStrength = 0f
            setAccentColor(appAccent, 0)
        }
        music = MusicView(this, videoKind).apply {
            onBack = { music.key(KeyEvent.KEYCODE_BACK) }
            onPermission = { requestPermissions(arrayOf(permission), REQUEST_AUDIO) }
            onPlay = { songs, id ->
                if (videoKind == null) control { it.play(songs, id) }
                else {
                    VideoPlayback.kind = requireNotNull(videoKind)
                    VideoPlayback.queue.set(songs, id)
                    VideoPlayback.position = 0
                    VideoPlayback.service?.play()
                    openVideo()
                }
            }
            onToggle = { if (videoKind == null) control { it.toggle() } else openVideo() }
            onSkip = { direction ->
                if (videoKind == null) control { if (direction < 0) it.previous() else it.next() }
                else if (VideoPlayback.kind == videoKind) {
                    if (direction < 0) VideoPlayback.queue.previous() else VideoPlayback.queue.next()
                    VideoPlayback.position = 0
                    VideoPlayback.service?.play()
                    openVideo()
                } else report("Choose a video to start playing.")
            }
            if (videoKind == null) {
                onShuffle = { control { it.shuffle() } }
                onRepeat = { control { it.repeatMode() } }
            }
            onCreatePlaylist = { createPlaylist() }
            onAddToPlaylist = ::addToPlaylist
            onArtwork = ::loadArtwork
        }
        val clock = ClockView(this)
        music.onUiAlpha = { clock.alpha = it }
        content = FrameLayout(this).apply {
            addView(particles, FrameLayout.LayoutParams(-1, -1))
            addView(music, FrameLayout.LayoutParams(-1, -1))
            addView(clock, FrameLayout.LayoutParams(-1, -1))
        }
        appSettings = ItemSettingsView(this).apply {
            prefs = HomePrefs(this@MusicActivity)
            particleSource = particles
            glowStrength = requireNotNull(prefs).glowIntensityOther / 100f
            onColorChanged = { particles.setAccentColor(it) }
            onCovered = { hidden ->
                music.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
                clock.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            }
            onClosed = {
                particles.setAccentColor(appAccent)
                music.refreshLayout()
                music.userActive()
                music.requestFocus()
            }
        }
        content.addView(appSettings, FrameLayout.LayoutParams(-1, -1))
        content.alpha = 0f
        setContentView(UiSurfaceHost.install(window, content))
        content.animate().alpha(1f).setDuration(APP_REVEAL_DURATION_MS).start()
        music.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        music.userActive()
        UserButtonShortcutService.onAppSettingsButton = ::settingsButtonPressed
        UserButtonShortcutService.onEclipseSource = { QuickSource(packageName, appTitle, eclipse = true) }
        if (videoKind != null) videoPlaybackChanged()
        UserButtonShortcutService.onCloseEclipse = {
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }

    override fun onPause() {
        music.suspendIdle()
        UserButtonShortcutService.onAppSettingsButton = null
        UserButtonShortcutService.onEclipseSource = null
        UserButtonShortcutService.onCloseEclipse = null
        super.onPause()
    }

    private fun settingsButtonPressed() {
        music.userActive()
        if (dialog?.isShowing == true || !content.isLaidOut) return
        if (appSettings.isShowing) appSettings.close()
        else appSettings.showApp(EclipseBundles(this, HomePrefs(this)).packagedItem(videoKind?.bundleId ?: BundleIds.MUSIC), content)
    }

    override fun onStart() {
        super.onStart()
        active = true
        music.refreshLayout()
        particles.setRunning(true)
        if (videoKind == null) {
            bound = bindService(Intent(this, MusicPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) report("Music playback service could not start.")
        } else VideoPlayback.listen(videoPlaybackChanged)
        stopListening = MediaLibraries.listen(this, videoKind, ::showLibrary)
        ContextCompat.registerReceiver(this, connectionsChanged, IntentFilter(MediaConnections.CONNECTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        load()
    }

    private fun load() {
        if (!active) return
        val localAllowed = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        music.localAccessMissing = !localAllowed
        music.needsPermission = !localAllowed
        if (localAllowed && !observing) {
            contentResolver.registerContentObserver(if (videoKind == null) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            observing = true
        }
        if (shownSongs == null) music.notice = "Loading library..."
        MediaLibraries.refresh(this, videoKind)
    }

    private fun showLibrary(snapshot: MediaLibrarySnapshot) {
        val changed = shownSongs != snapshot.songs || playlists != snapshot.playlists
        playlists = snapshot.playlists
        music.localAccessMissing = !snapshot.localAllowed
        music.needsPermission = !snapshot.localAllowed && snapshot.songs.isEmpty()
        music.notice = if (snapshot.songs.isEmpty()) snapshot.errors.firstOrNull()?.message else null
        if (changed || snapshot.refreshArtwork) {
            artworkGeneration++
            requestedArt.clear()
            music.artwork.evictAll()
            music.library(snapshot.songs, snapshot.playlists)
        }
        shownSongs = snapshot.songs
    }

    private fun control(action: (MusicPlaybackService) -> Unit) {
        service?.let(action) ?: report("Music playback is not ready. Try again.")
    }

    private fun openVideo() {
        val kind = videoKind ?: return
        if (VideoPlayback.queue.current == null || VideoPlayback.kind != kind) {
            report("Choose a video to start playing.")
            return
        }
        startActivity(Intent(this, VideoPlayerActivity::class.java).putExtra("kind", kind.key))
    }

    private fun createPlaylist(song: MusicSong? = null) {
        dialog = tvTextEntry(this, "New playlist", "", false, validate = { name ->
            when {
                name.trim().isEmpty() -> "Enter a playlist name."
                playlists.any { it.name.equals(name.trim(), true) } -> "A playlist with that name already exists."
                else -> null
            }
        }, onDismiss = { dialog = null }, maxLength = 48) { name ->
            work({
                val playlist = library.createPlaylist(name)
                if (song != null) library.addToPlaylist(playlist.id, song.id)
            }) { load() }
        }
    }

    private fun addToPlaylist(song: MusicSong) {
        val choices = playlists.toList()
        dialog = AlertDialog.Builder(this, R.style.Theme_Eclipse_Input)
            .setTitle("Add ${song.title} to playlist")
            .setItems((listOf("New playlist") + choices.map { it.name }).toTypedArray()) { _, index ->
                main.post {
                    if (active) {
                        if (index == 0) createPlaylist(song)
                        else work({ library.addToPlaylist(choices[index - 1].id, song.id) }) {
                            Toast.makeText(this, "Added to ${choices[index - 1].name}", Toast.LENGTH_SHORT).show()
                            load()
                        }
                    }
                }
            }.setNegativeButton("Cancel", null).create().also {
                it.setOnDismissListener { dialog = null }
                it.show()
                UiSurfaceHost.install(it)
            }
    }

    private fun loadArtwork(song: MusicSong) {
        if (song.connectionId != null && song.sourceArtwork == null) return
        if (!active || !requestedArt.add(song.albumId)) return
        val request = artworkGeneration
        worker.execute {
            val bitmap = try {
                if (song.connectionId != null) MediaConnections.artwork(this, song)
                else if (videoKind != null) contentResolver.loadThumbnail(song.uri, android.util.Size(320, 320), null)
                else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(song.artwork)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    contentResolver.openInputStream(song.artwork)?.use { input ->
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            while (bounds.outWidth / inSampleSize > 640 || bounds.outHeight / inSampleSize > 640) inSampleSize *= 2
                        }
                        BitmapFactory.decodeStream(input, null, options)?.let { original ->
                            if (original.width <= 320 && original.height <= 320) original
                            else {
                                val ratio = 320f / maxOf(original.width, original.height)
                                Bitmap.createScaledBitmap(original, (original.width * ratio).toInt().coerceAtLeast(1),
                                    (original.height * ratio).toInt().coerceAtLeast(1), true).also { original.recycle() }
                            }
                        }
                    }
                }
            } catch (_: FileNotFoundException) {
                if (videoKind != null) Log.w("Music", "Video thumbnail is unavailable for ${song.title}")
                null
            } catch (e: IOException) {
                Log.w("Music", "Could not load album artwork", e); null
            } catch (e: SecurityException) {
                Log.w("Music", "Album artwork access was denied", e); null
            } catch (e: GeneralSecurityException) {
                main.post { report("Reconnect your media server in Settings > Connections.", e) }; null
            } catch (e: JSONException) {
                main.post { report("The saved media connection is damaged. Reconnect in Settings.", e) }; null
            }
            main.post {
                if (!isDestroyed && request == artworkGeneration && bitmap != null) {
                    music.artwork.put(song.albumId, bitmap)
                    music.invalidate()
                } else bitmap?.recycle()
            }
        }
    }

    private fun <T> work(operation: () -> T, success: (T) -> Unit) {
        worker.execute {
            val result = try { Result.success(operation()) }
            catch (e: SecurityException) { Result.failure(e) }
            catch (e: IllegalStateException) { Result.failure(e) }
            catch (e: IllegalArgumentException) { Result.failure(e) }
            catch (e: SQLException) { Result.failure(e) }
            catch (e: JSONException) { Result.failure(e) }
            main.post {
                if (active && !isDestroyed) result.fold(success) {
                    music.notice = it.message ?: "Could not read the music library."
                    report(requireNotNull(music.notice), it)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_AUDIO) {
            if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) report("Allow media access to browse local files.")
            load()
        }
    }

    override fun dispatchKeyEvent(rawEvent: KeyEvent): Boolean {
        if (::music.isInitialized && rawEvent.action == KeyEvent.ACTION_DOWN) music.userActive()
        if (!::music.isInitialized || dialog?.isShowing == true) return super.dispatchKeyEvent(rawEvent)
        val event = remoteMapping.mappedEvent(rawEvent)
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_SETTINGS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) settingsButtonPressed()
            return true
        }
        if (appSettings.isShowing) return appSettings.handleKey(event)
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) music.key(code)
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) music.longSelect()
                else music.key(code)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && code in setOf(KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            return music.key(code)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (code) {
                KeyEvent.KEYCODE_MENU -> {
                    if (music.localAccessMissing) requestPermissions(arrayOf(permission), REQUEST_AUDIO) else load()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { music.onToggle(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { music.onSkip(1); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { music.onSkip(-1); return true }
            }
            if (music.key(code)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun report(message: String, error: Throwable? = null) {
        Log.w("Music", message, error)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        VideoPlayback.unlisten(videoPlaybackChanged)
        appSettings.dismiss()
        active = false
        stopListening?.invoke()
        stopListening = null
        unregisterReceiver(connectionsChanged)
        particles.setRunning(false)
        main.removeCallbacks(refresh)
        if (observing) { contentResolver.unregisterContentObserver(observer); observing = false }
        service?.unlisten(playbackChanged)
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object { const val REQUEST_AUDIO = 924 }
}
