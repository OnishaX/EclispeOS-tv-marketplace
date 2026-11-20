package com.example.eclipse

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

internal data class MusicSong(
    val id: Long, val title: String, val artist: String, val artistId: Long,
    val album: String, val albumId: Long, val duration: Long, val track: Int,
    val sourceUri: Uri? = null, val sourceArtwork: Uri? = null, val connectionId: String? = null,
    val genres: List<String> = emptyList(), val collections: List<String> = emptyList(),
) {
    val uri: Uri get() = sourceUri ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val artwork: Uri get() = sourceArtwork ?: if (connectionId != null) Uri.EMPTY else Uri.parse("content://media/external/audio/albumart/$albumId")
}

internal data class MusicPlaylist(val id: String, val name: String, val songs: List<Long>)
internal data class MusicCollection(val id: String, val title: String, val subtitle: String, val songs: List<MusicSong>)
internal enum class MusicSection(val title: String) { ARTISTS("Artists"), ALBUMS("Albums"), SONGS("Songs"), PLAYLISTS("Playlists") }
internal enum class MusicRepeat { NONE, ALL, ONE }
private fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"

internal fun musicCollections(section: MusicSection, songs: List<MusicSong>, playlists: List<MusicPlaylist>): List<MusicCollection> =
    when (section) {
        MusicSection.ARTISTS -> songs.groupBy { it.artistId }.map { (id, tracks) ->
            MusicCollection("artist:$id", tracks.first().artist, songCount(tracks.size),
                tracks.sortedWith(compareBy<MusicSong> { it.album.lowercase() }.thenBy { it.track }.thenBy { it.title.lowercase() }))
        }.sortedBy { it.title.lowercase() }
        MusicSection.ALBUMS -> songs.groupBy { it.albumId }.map { (id, tracks) ->
            MusicCollection("album:$id", tracks.first().album, tracks.first().artist,
                tracks.sortedWith(compareBy<MusicSong> { it.track }.thenBy { it.title.lowercase() }))
        }.sortedBy { it.title.lowercase() }
        MusicSection.PLAYLISTS -> {
            val byId = songs.associateBy { it.id }
            playlists.map { playlist ->
                val tracks = playlist.songs.mapNotNull { byId[it] }
                MusicCollection(playlist.id, playlist.name, songCount(tracks.size), tracks)
            }.sortedBy { it.title.lowercase() }
        }
        MusicSection.SONGS -> emptyList()
    }

internal class MusicLibrary(context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("music_library", Context.MODE_PRIVATE)

    fun songs(): List<MusicSong> {
        val columns = arrayOf("_id", "title", "artist", "artist_id", "album", "album_id", "duration", "track")
        val cursor = checkNotNull(resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")) {
            "Android could not read the music library."
        }
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(MusicSong(it.getLong(0), it.getString(1)?.takeIf(String::isNotBlank) ?: "Untitled",
                    it.getString(2)?.takeUnless { name -> name.isBlank() || name == "<unknown>" } ?: "Unknown artist",
                    it.getLong(3), it.getString(4)?.takeIf(String::isNotBlank) ?: "Unknown album",
                    it.getLong(5), it.getLong(6).coerceAtLeast(0), it.getInt(7)))
            }
        }
    }

    fun playlists(): List<MusicPlaylist> {
        val array = JSONArray(prefs.getString("playlists", "[]"))
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val songs = item.getJSONArray("songs")
            MusicPlaylist(item.getString("id"), item.getString("name"), List(songs.length()) { songs.getLong(it) })
        }
    }

    fun createPlaylist(name: String): MusicPlaylist {
        require(name.trim().length in 1..48) { "Use a playlist name between 1 and 48 characters." }
        val all = playlists()
        require(all.none { it.name.equals(name.trim(), ignoreCase = true) }) { "A playlist with that name already exists." }
        val playlist = MusicPlaylist(UUID.randomUUID().toString(), name.trim(), emptyList())
        save(all + playlist)
        return playlist
    }

    fun addToPlaylist(id: String, song: Long) {
        val all = playlists()
        check(all.any { it.id == id }) { "This playlist no longer exists." }
        save(all.map { if (it.id == id) it.copy(songs = (it.songs + song).distinct()) else it })
    }

    private fun save(playlists: List<MusicPlaylist>) {
        val array = JSONArray()
        playlists.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("songs", JSONArray(it.songs))) }
        check(prefs.edit().putString("playlists", array.toString()).commit()) { "Could not save your playlists." }
    }
}

internal class MusicQueue(private val random: Random = Random.Default) {
    private var original = emptyList<MusicSong>()
    private var order = emptyList<MusicSong>()
    private var index = -1
    var shuffle = false
        private set
    var repeat = MusicRepeat.NONE
    val current get() = order.getOrNull(index)

    fun set(songs: List<MusicSong>, id: Long) {
        require(songs.any { it.id == id }) { "The selected song is no longer available." }
        original = songs.distinctBy { it.id }
        order = if (shuffle) original.filterNot { it.id == id }.shuffled(random).let { rest ->
            listOf(original.first { it.id == id }) + rest
        } else original
        index = order.indexOfFirst { it.id == id }
    }

    fun toggleShuffle() {
        val song = current
        shuffle = !shuffle
        order = if (shuffle) original.filterNot { it.id == song?.id }.shuffled(random).let {
            if (song == null) it else listOf(song) + it
        } else original
        index = order.indexOfFirst { it.id == song?.id }
    }

    fun next(automatic: Boolean = false): MusicSong? {
        if (order.isEmpty()) return null
        if (automatic && repeat == MusicRepeat.ONE) return current
        val next = index + 1
        if (next >= order.size && repeat == MusicRepeat.NONE) return null
        index = next % order.size
        return current
    }

    fun previous(): MusicSong? {
        if (order.isEmpty()) return null
        index = if (index > 0) index - 1 else if (repeat != MusicRepeat.NONE) order.lastIndex else 0
        return current
    }
}
