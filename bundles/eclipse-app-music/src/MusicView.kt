package com.example.eclipse

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

internal class MusicView(context: Context, private val videoKind: VideoKind? = null) : View(context) {
    private val accent get() = videoKind?.accent ?: 0xFFEC31BD.toInt()
    private val appTitle get() = videoKind?.title ?: "Music"
    private val noun get() = if (videoKind == null) "music" else "videos"
    private val sections = videoKind?.sections ?: MusicSection.entries
    private val transportIndex get() = sections.size
    private val showModes get() = videoKind == null
    private val footerRows get() = if (showModes) 2 else 1
    private fun sectionTitle(section: MusicSection) = videoKind?.labels?.get(section.ordinal) ?: section.title
    var localAccessMissing = false
    var onPlay: (List<MusicSong>, Long) -> Unit = { _, _ -> }
    var onToggle: () -> Unit = {}
    var onSkip: (Int) -> Unit = {}
    var onShuffle: () -> Unit = {}
    var onRepeat: () -> Unit = {}
    var onPermission: () -> Unit = {}
    var onBack: () -> Unit = {}
    var onCreatePlaylist: () -> Unit = {}
    var onAddToPlaylist: (MusicSong) -> Unit = {}
    var onArtwork: (MusicSong) -> Unit = {}
    var playback = MusicPlayback(null, false, false, false, MusicRepeat.NONE)
        set(value) { field = value; describe(); invalidate() }
    var notice: String? = "Loading local music..."
        set(value) { field = value; invalidate() }
    var needsPermission = false
        set(value) { field = value; updateFocus(); invalidate() }
    val artwork = LruCache<Long, Bitmap>(24)
    private var songs = emptyList<MusicSong>()
    private var collections = emptyMap<MusicSection, List<MusicCollection>>()
    private var section = MusicSection.ARTISTS
    private var collection: String? = null
    private var sidebar = 0
    private var right = false
    private var selected = 0
    private var scroll = 0
    private val painter = CardPainter(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val clip = Path()
    private val rect = RectF()
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = SettingsChrome.focusGradient(accent)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val glowStrength = HomePrefs(context).glowIntensityOther / 100f
    private var glowX = 170f
    private var glowY = SettingsChrome.TOP + SettingsChrome.ROW_HEIGHT / 2
    private var focusPosition: RectF? = null
    private var focusTarget: RectF? = null
    private var focusAnimator: ValueAnimator? = null
    private val groups get() = collections[section].orEmpty().let {
        if (section == MusicSection.PLAYLISTS) listOf(MusicCollection(NEW, "New playlist", "Create a playlist", emptyList())) + it else it
    }
    private val opened get() = groups.firstOrNull { it.id == collection }
    private val list get() = (section == MusicSection.SONGS && videoKind != VideoKind.MOVIES) || collection != null
    private val tracks get() = opened?.songs ?: if (collection == null) songs else emptyList()
    private val count get() = if (needsPermission) 1 else if (list) tracks.size else groups.size

    init { isFocusable = true; isFocusableInTouchMode = true }

    fun library(songs: List<MusicSong>, playlists: List<MusicPlaylist>) {
        val selectedSong = if (list) tracks.getOrNull(selected)?.id else null
        val selectedGroup = if (!list) groups.getOrNull(selected)?.id else null
        this.songs = songs
        collections = sections.associateWith {
            if (videoKind == null) musicCollections(it, songs, playlists) else videoCollections(videoKind, it, songs)
        }
        if (collection != null && opened == null) collection = null
        val retained = if (list) tracks.indexOfFirst { it.id == selectedSong } else groups.indexOfFirst { it.id == selectedGroup }
        if (retained >= 0) selected = retained
        selected = selected.coerceAtMost((count - 1).coerceAtLeast(0))
        keepVisible()
        describe()
        invalidate()
    }

    fun key(code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                if (collection != null) { collection = null; selected = 0; scroll = 0 }
                else if (right || sidebar >= transportIndex) { right = false; sidebar = sections.indexOf(section) }
                else return false
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (right) activate()
                else when (sidebar) {
                    transportIndex -> onToggle()
                    transportIndex + 1 -> onShuffle()
                    transportIndex + 2 -> onRepeat()
                    else -> right = count > 0
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val delta = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (!right) when (sidebar) {
                    transportIndex -> onSkip(delta)
                    transportIndex + 1, transportIndex + 2 ->
                        sidebar = (sidebar + delta).coerceIn(transportIndex + 1, transportIndex + 2)
                    else -> if (delta > 0) right = count > 0
                } else if (delta < 0) {
                    right = false
                    sidebar = sections.indexOf(section)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val delta = if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                if (right) {
                    selected = (selected + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
                } else {
                    sidebar = when {
                        sidebar > transportIndex -> if (delta < 0) transportIndex else sidebar
                        else -> (sidebar + delta).coerceIn(0, transportIndex + if (showModes) 1 else 0)
                    }
                    if (sidebar < transportIndex && section != sections[sidebar]) {
                        section = sections[sidebar]
                        collection = null; selected = 0; scroll = 0
                    }
                }
            }
            else -> return false
        }
        keepVisible()
        describe()
        invalidate()
        return true
    }

    fun longSelect() {
        if (videoKind == null && right && list) tracks.getOrNull(selected)?.let(onAddToPlaylist)
    }

    private fun activate() {
        if (needsPermission) { onPermission(); return }
        if (list) tracks.getOrNull(selected)?.let { onPlay(tracks, it.id) }
        else groups.getOrNull(selected)?.let {
            if (it.id == NEW) onCreatePlaylist()
            else if (videoKind == VideoKind.MOVIES && section == MusicSection.ARTISTS) {
                onPlay(groups.flatMap { group -> group.songs }, it.songs.first().id)
            }
            else { collection = it.id; selected = 0; scroll = 0 }
        }
    }

    private fun keepVisible() {
        if (right && count == 0) { right = false; sidebar = sections.indexOf(section) }
        val row = selected
        val visible = VISIBLE_ROWS
        if (row < scroll) scroll = row
        if (row >= scroll + visible) scroll = row - visible + 1
        scroll = scroll.coerceIn(0, (count - visible).coerceAtLeast(0))
    }

    private fun describe() {
        val focus = if (right) {
            if (needsPermission) "Allow $noun access"
            else if (list) tracks.getOrNull(selected)?.title.orEmpty() else groups.getOrNull(selected)?.title.orEmpty()
        } else when (sidebar) {
            transportIndex -> "Playback controls. Left previous, Select play or pause, Right next."
            transportIndex + 1 -> "Shuffle ${if (playback.shuffle) "on" else "off"}"
            transportIndex + 2 -> "Repeat ${playback.repeat.name.lowercase()}"
            else -> sectionTitle(sections[sidebar])
        }
        contentDescription = "$appTitle. ${opened?.title ?: sectionTitle(section)}. Focus: $focus. " +
            "${playback.song?.title.orEmpty()}. ${if (playback.playing) "Playing" else "Paused"}"
        updateFocus()
    }

    private fun updateFocus() {
        val target = when {
            !right && sidebar < transportIndex -> {
                val y = SettingsChrome.TOP + sidebar * SettingsChrome.ROW_HEIGHT
                RectF(SettingsChrome.LEFT, y, SettingsChrome.SIDEBAR_RIGHT, y + SettingsChrome.ROW_HEIGHT)
            }
            !right && sidebar == transportIndex -> RectF(SettingsChrome.LEFT, footerTop(footerRows), SettingsChrome.SIDEBAR_RIGHT, footerTop(footerRows) + FOOTER_ROW_HEIGHT)
            !right && sidebar == transportIndex + 1 -> RectF(125f, footerTop(1), 190f, FOOTER_BOTTOM)
            !right -> RectF(215f, footerTop(1), 280f, FOOTER_BOTTOM)
            else -> {
                val y = SettingsChrome.TOP + (selected - scroll) * SettingsChrome.ROW_HEIGHT
                RectF(SettingsChrome.CONTENT_LEFT, y, SettingsChrome.CONTENT_RIGHT, y + SettingsChrome.ROW_HEIGHT)
            }
        }
        if (target == focusTarget) return
        focusTarget = target
        focusAnimator?.cancel()
        val start = focusPosition?.let(::RectF)
        focusPosition = RectF(target)
        val targetX = if (!right) 134f else 620f
        val targetY = target.centerY()
        if (start == null) {
            focusPosition = RectF(target)
            glowX = targetX
            glowY = targetY
            return
        }
        val startX = glowX
        val startY = glowY
        focusAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            addUpdateListener {
                val t = it.animatedFraction
                glowX = lerp(startX, targetX, t)
                glowY = lerp(startY, targetY, t)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.scale(width / MOCK_W, width / MOCK_W)
        if (focusPosition == null) updateFocus()
        halo(canvas)
        painter.drawIcon(canvas, R.drawable.ic_chevron_left, 46f, 48f, 20f, Color.WHITE, 1f)
        SettingsChrome.divider(canvas, line)
        if (!needsPermission && notice == null && count > 0) {
            drawPaneScrollIndicator(canvas, line, scroll.toFloat(), (count - VISIBLE_ROWS).coerceAtLeast(0).toFloat(),
                accent, PANE_SEPARATOR_ALPHA)
        }
        sections.forEachIndexed { index, item ->
            val top = SettingsChrome.TOP + index * SettingsChrome.ROW_HEIGHT
            val mid = top + SettingsChrome.ROW_HEIGHT / 2
            val focused = !right && sidebar == index
            painter.drawIcon(canvas, sectionIcon(item), SettingsChrome.LEFT + 12f, mid - 1f, 24f, Color.WHITE, 0.95f)
            label(canvas, sectionTitle(item), SettingsChrome.LEFT + 37f, mid + 7f, 22f, 177f, 0.95f,
                glow = if (focused) 1f else if (section == item) 0.5f else 0f)
            if (section == item && !focused) {
                SettingsChrome.hairline(canvas, line, SettingsChrome.LEFT, top + SettingsChrome.ROW_HEIGHT - 1f, SettingsChrome.SIDEBAR_RIGHT, accent, 0.35f)
            }
        }
        drawPlayback(canvas)
        if (needsPermission) {
            painter.drawIcon(canvas, videoKind?.icon ?: R.drawable.ic_music_songs, 403f, 83f, 24f, Color.WHITE, 0.95f)
            label(canvas, "Allow $noun access", 428f, 91f, 22f, 830f, 0.95f, glow = if (right) 1f else 0f)
            label(canvas, "Browse $noun stored on your TV and connected storage.", 428f, 119f, 18f, 830f,
                color = Color.LTGRAY)
        } else if (notice != null) {
            label(canvas, requireNotNull(notice), 428f, 91f, 22f, 830f, 0.95f)
        } else if (count == 0) {
            painter.drawIcon(canvas, sectionIcon(section), 403f, 83f, 24f, Color.WHITE, 0.95f)
            label(canvas, if (collection != null) "This playlist is empty." else "No $noun found.", 428f, 91f, 22f, 830f, 0.95f)
            label(canvas, "Add local $noun or connect a server in Settings.", 428f, 119f, 18f, 830f, color = Color.LTGRAY)
        } else {
            val content = canvas.save()
            canvas.clipRect(SettingsChrome.CONTENT_LEFT, SettingsChrome.PANE_TOP,
                SettingsChrome.CONTENT_RIGHT, SettingsChrome.PANE_BOTTOM)
            if (list) drawSongs(canvas) else drawCollections(canvas)
            canvas.restoreToCount(content)
        }
        focusPosition?.let { bounds ->
            SettingsChrome.hairline(canvas, line, bounds.left, bounds.bottom - 1f, bounds.right, accent, 0.8f)
        }
        canvas.restoreToCount(save)
    }

    private fun drawPlayback(canvas: Canvas) {
        val controlsTop = footerTop(footerRows)
        SettingsChrome.centeredRule(canvas, line, SettingsChrome.LEFT, controlsTop - SettingsChrome.FOOTER_DIVIDER_GAP, SettingsChrome.SIDEBAR_RIGHT)
        playback.song?.let {
            label(canvas, it.title, SettingsChrome.LEFT, controlsTop - 69f, 18f, 214f)
            label(canvas, if (playback.loading) "Loading..." else it.artist, SettingsChrome.LEFT, controlsTop - 42f, 18f, 214f, color = 0xFFAAAAAA.toInt())
        }
        val controlsY = controlsTop + FOOTER_ROW_HEIGHT / 2
        val modesY = footerTop(1) + FOOTER_ROW_HEIGHT / 2
        painter.drawIcon(canvas, R.drawable.ic_music_previous, 118f, controlsY, 24f, Color.WHITE, 0.95f)
        painter.drawIcon(canvas, if (playback.playing || playback.loading) R.drawable.ic_cc_pause else R.drawable.ic_cc_play,
            211f, controlsY, 24f, Color.WHITE, 0.95f)
        painter.drawIcon(canvas, R.drawable.ic_music_next, 308f, controlsY, 24f, Color.WHITE, 0.95f)
        if (showModes) {
            painter.drawIcon(canvas, R.drawable.ic_music_shuffle, 158f, modesY, 24f,
                if (playback.shuffle) accent else Color.WHITE, 0.95f)
            painter.drawIcon(canvas, if (playback.repeat == MusicRepeat.ONE) R.drawable.ic_music_repeat_one else R.drawable.ic_music_repeat,
                248f, modesY, 24f, if (playback.repeat != MusicRepeat.NONE) accent else Color.WHITE, 0.95f)
        }
    }

    private fun drawSongs(canvas: Canvas) {
        tracks.forEachIndexed { index, song ->
            if (index !in scroll until scroll + VISIBLE_ROWS) return@forEachIndexed
            val mid = SettingsChrome.TOP + (index - scroll + 0.5f) * SettingsChrome.ROW_HEIGHT
            val playing = song.id == playback.song?.id
            val icon = if (playing) {
                if (playback.playing) R.drawable.ic_cc_pause else R.drawable.ic_cc_play
            } else videoKind?.icon ?: R.drawable.ic_music_songs
            if (videoKind != null) drawArtwork(canvas, song, mid) else {
                painter.drawIcon(canvas, icon, 403f, mid - 1f, 24f, if (playing) accent else Color.WHITE, 0.95f)
            }
            val textX = if (videoKind != null) 437f else 428f
            label(canvas, song.title, textX, mid + 7f, 22f, 700f, 0.95f, glow = if (right && selected == index) 1f else 0f)
            label(canvas, "${song.artist}  /  ${song.album}", textX, mid + 26f, 18f, 700f, color = 0xFFAAAAAA.toInt())
            label(canvas, "${song.duration / 60000}:${(song.duration / 1000 % 60).toString().padStart(2, '0')}",
                SettingsChrome.CONTENT_RIGHT, mid + 6f, 18f, 73f, color = 0xFFAAAAAA.toInt(), align = Paint.Align.RIGHT)
        }
    }

    private fun drawCollections(canvas: Canvas) {
        groups.forEachIndexed { index, group ->
            if (index !in scroll until scroll + VISIBLE_ROWS) return@forEachIndexed
            val mid = SettingsChrome.TOP + (index - scroll + 0.5f) * SettingsChrome.ROW_HEIGHT
            val song = group.songs.firstOrNull()
            if (song != null) drawArtwork(canvas, song, mid)
            else painter.drawIcon(canvas, if (group.id == NEW) R.drawable.ic_music_playlist else sectionIcon(section),
                403f, mid - 1f, 24f, Color.WHITE, 0.95f)
            val textX = if (videoKind != null) 437f else 449f
            label(canvas, group.title, textX, mid + 7f, 22f, SettingsChrome.CONTENT_RIGHT - textX, 0.95f,
                glow = if (right && selected == index) 1f else 0f)
            label(canvas, group.subtitle, textX, mid + 26f, 18f, SettingsChrome.CONTENT_RIGHT - textX,
                color = 0xFFAAAAAA.toInt())
        }
    }

    private fun drawArtwork(canvas: Canvas, song: MusicSong, mid: Float) {
        val art = artwork.get(song.albumId)
        val coverWidth = if (videoKind == null) 42f else 28f
        rect.set(SettingsChrome.CONTENT_LEFT, mid - 13f, SettingsChrome.CONTENT_LEFT + coverWidth, mid + 29f)
        paint.shader = null
        paint.color = 0xFF171719.toInt()
        canvas.drawRoundRect(rect, 4f, 4f, paint)
        if (art == null) {
            onArtwork(song)
            painter.drawIcon(canvas, sectionIcon(section), rect.centerX(), rect.centerY(), 22f, Color.WHITE, 0.95f)
            return
        }
        val artSave = canvas.save()
        clip.reset()
        clip.addRoundRect(rect, 4f, 4f, Path.Direction.CW)
        canvas.clipPath(clip)
        val scale = maxOf(rect.width() / art.width, rect.height() / art.height)
        val cropWidth = (rect.width() / scale).roundToInt().coerceIn(1, art.width)
        val cropHeight = (rect.height() / scale).roundToInt().coerceIn(1, art.height)
        val left = (art.width - cropWidth) / 2
        val top = (art.height - cropHeight) / 2
        val src = android.graphics.Rect(left, top, left + cropWidth, top + cropHeight)
        canvas.drawBitmap(art, src, rect, paint)
        canvas.restoreToCount(artSave)
    }

    private fun sectionIcon(section: MusicSection): Int = videoKind?.icons?.get(section.ordinal) ?: when (section) {
        MusicSection.ARTISTS -> R.drawable.ic_users
        MusicSection.ALBUMS -> R.drawable.ic_music_album
        MusicSection.SONGS -> R.drawable.ic_music_songs
        MusicSection.PLAYLISTS -> R.drawable.ic_music_playlist
    }

    private fun label(canvas: Canvas, value: String, x: Float, y: Float, size: Float, width: Float, alpha: Float = 1f,
        color: Int = Color.WHITE, glow: Float = 0f, align: Paint.Align = Paint.Align.LEFT) {
        text.textSize = size
        val fit = TextUtils.ellipsize(value, text, width, TextUtils.TruncateAt.END).toString()
        painter.drawLabel(canvas, fit, x, y, size, color, alpha, glow = glow * glowStrength, align = align, glowColor = accent)
    }

    private fun halo(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(glowX, glowY)
        canvas.scale(SettingsChrome.GLOW_WIDTH, SettingsChrome.GLOW_HEIGHT)
        focusPaint.alpha = (255 * glowStrength).toInt()
        canvas.drawCircle(0f, 0f, 1f, focusPaint)
        canvas.restoreToCount(save)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x * MOCK_W / width
        val y = event.y * MOCK_W / width
        if (x < 90f && y < SettingsChrome.PANE_TOP) { onBack(); return true }
        if (x < SettingsChrome.DIVIDER_X) {
            right = false
            when {
                y >= SettingsChrome.TOP && y < SettingsChrome.TOP + sections.size * SettingsChrome.ROW_HEIGHT -> {
                    sidebar = ((y - SettingsChrome.TOP) / SettingsChrome.ROW_HEIGHT).toInt().coerceIn(sections.indices)
                    section = sections[sidebar]; collection = null; selected = 0; scroll = 0 }
                y >= footerTop(footerRows) && y < footerTop(footerRows) + FOOTER_ROW_HEIGHT -> { sidebar = transportIndex
                    if (x < 169f) onSkip(-1) else if (x > 251f) onSkip(1) else onToggle() }
                showModes && y >= footerTop(1) && y < FOOTER_BOTTOM -> { sidebar = transportIndex + if (x < 203f) 1 else 2
                    if (sidebar == transportIndex + 1) onShuffle() else onRepeat() }
            }
        } else if (x in SettingsChrome.CONTENT_LEFT..SettingsChrome.CONTENT_RIGHT && y >= SettingsChrome.PANE_TOP &&
            y < SettingsChrome.PANE_BOTTOM && count > 0) {
            right = true
            val next = scroll + ((y - SettingsChrome.TOP) / SettingsChrome.ROW_HEIGHT).toInt()
            if (next in 0 until count) { selected = next; activate() }
        }
        keepVisible(); describe(); invalidate()
        performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDetachedFromWindow() {
        focusAnimator?.cancel()
        focusAnimator = null
        focusPosition = null
        focusTarget = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val NEW = "__new_playlist__"
        const val VISIBLE_ROWS = 10
    }
}
