package com.example.eclipse

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal object MusicArtwork {
    private data class Artist(val profile: String, val connection: String?, val credits: List<MusicArtist>)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()
    private var task: Future<*>? = null
    private var generation = 0
    private var requested: Pair<Artist, Long>? = null
    private var artistImages = false
    private var playing = false
    private var slideshow: ArtworkSlideshow? = null
    private var profiles: SharedPreferences? = null
    private val profileChanged = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == UserProfiles.ACTIVE &&
            prefs.getString(key, UserProfiles.DEFAULT_ID) != requested?.first?.profile) clear()
    }
    val preview: ArtworkSlideshow? get() = slideshow.takeIf { playing }
    private val tick = object : Runnable {
        override fun run() {
            listeners.toList().forEach { it() }
            if (preview != null && listeners.isNotEmpty()) main.postDelayed(this, 33)
        }
    }

    fun listenFrames(listener: () -> Unit) { listeners.add(listener); changed() }
    fun unlistenFrames(listener: () -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) main.removeCallbacks(tick)
    }

    fun update(context: Context, state: MusicPlayback) {
        val song = state.song ?: run { clear(); return }
        val wasPlaying = playing
        playing = state.playing
        if (!playing) {
            if (wasPlaying) changed()
            return
        }
        val app = context.applicationContext
        val prefs = profiles ?: app.getSharedPreferences(UserProfiles.STORAGE, Context.MODE_PRIVATE).also {
            profiles = it
            it.registerOnSharedPreferenceChangeListener(profileChanged)
        }
        val artist = Artist(prefs.getString(UserProfiles.ACTIVE, UserProfiles.DEFAULT_ID) ?: UserProfiles.DEFAULT_ID,
            song.connectionId, song.artists)
        val key = artist to song.albumId
        if (requested == key || (artistImages && requested?.first == artist)) {
            if (!wasPlaying) changed()
            return
        }
        requested = key
        artistImages = false
        slideshow = null
        val request = ++generation
        task?.cancel(true)
        changed()
        task = worker.submit {
            val portraits = readMediaSource { MediaConnections.artistArtwork(app, song) }
            portraits.error?.let { Log.w("MusicArtwork", "Artist artwork unavailable; trying album artwork", it) }
            if (Thread.currentThread().isInterrupted) return@submit
            val useArtist = portraits.items.isNotEmpty()
            val images = if (useArtist) portraits.items else {
                val album = readMediaSource { listOfNotNull(album(app, song)) }
                album.error?.let { Log.w("MusicArtwork", "Album artwork is unavailable", it) }
                album.items
            }
            main.post {
                if (request == generation) {
                    artistImages = useArtist
                    slideshow = images.takeIf { it.isNotEmpty() }?.let { ArtworkSlideshow(it) }
                    if (images.isEmpty()) requested = null
                    changed()
                } else images.forEach { it.recycle() }
            }
        }
    }

    fun clear() {
        generation++
        task?.cancel(true)
        task = null
        profiles?.unregisterOnSharedPreferenceChangeListener(profileChanged)
        profiles = null
        requested = null
        slideshow = null
        artistImages = false
        playing = false
        changed()
    }

    private fun changed() {
        main.removeCallbacks(tick)
        listeners.toList().forEach { it() }
        if (preview != null && listeners.isNotEmpty()) main.postDelayed(tick, 33)
    }

    private fun album(context: Context, song: MusicSong): Bitmap? {
        if (song.connectionId != null) return MediaConnections.artwork(context, song)
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = resolver.openInputStream(song.artwork) ?: throw FileNotFoundException("No local album artwork.")
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) throw IOException("Invalid local album artwork.")
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 1280 || bounds.outHeight / inSampleSize > 1280) inSampleSize *= 2
        }
        return resolver.openInputStream(song.artwork)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Unreadable local album artwork.")
    }
}
