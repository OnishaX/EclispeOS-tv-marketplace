package com.example.eclipse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.cos

class ArtworkSlideshow(images: List<Bitmap>, private val startedAt: Long = SystemClock.uptimeMillis(),
    previous: Bitmap? = null, fadeIn: Boolean = false) {
    private val images = images.toList()
    private var previous = previous
    private var fadeStartedAt: Long? = if (fadeIn) startedAt else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    init { require(images.isNotEmpty() || (fadeIn && previous != null)) { "A slideshow needs images or an outgoing frame." } }

    internal fun beginFade() { previous = null; fadeStartedAt = SystemClock.uptimeMillis() }
    internal val fadingOut get() = images.isEmpty()
    internal fun isFinished(now: Long = SystemClock.uptimeMillis()) =
        fadingOut && now - (fadeStartedAt ?: startedAt) >= CHANGE_FADE_MS

    fun draw(canvas: Canvas, bounds: RectF, alpha: Float = 1f, now: Long = SystemClock.uptimeMillis()) {
        val saved = canvas.save()
        canvas.clipRect(bounds)
        if (alpha < 1f) canvas.saveLayerAlpha(bounds, (alpha.coerceIn(0f, 1f) * 255).toInt())
        val fade = fadeStartedAt?.let { ((now - it).toFloat() / CHANGE_FADE_MS).coerceIn(0f, 1f) } ?: 1f
        if (fade < 1f) previous?.let { outgoing ->
            val scale = maxOf(bounds.width() / outgoing.width, bounds.height() / outgoing.height)
            val w = outgoing.width * scale / 2f
            val h = outgoing.height * scale / 2f
            destination.set(bounds.centerX() - w, bounds.centerY() - h, bounds.centerX() + w, bounds.centerY() + h)
            paint.alpha = if (images.isEmpty()) (255 * (1f - fade)).toInt() else 255
            canvas.drawBitmap(outgoing, null, destination, paint)
        } else previous = null
        if (images.isNotEmpty() && fade > 0f) {
            val incoming = canvas.save()
            if (fade < 1f) canvas.saveLayerAlpha(bounds, (255 * fade).toInt())
            drawImages(canvas, bounds, now)
            canvas.restoreToCount(incoming)
        }
        canvas.restoreToCount(saved)
    }

    private fun drawImages(canvas: Canvas, bounds: RectF, now: Long) {
        val elapsed = (now - startedAt).coerceAtLeast(0)
        val frame = elapsed / SLIDE_MS
        val age = elapsed % SLIDE_MS
        val index = (frame % images.size).toInt()
        val motion = if (images.size == 1) ((1 - cos(elapsed * Math.PI / (SLIDE_MS + FADE_MS))) / 2).toFloat()
            else (age + FADE_MS).toFloat() / (SLIDE_MS + FADE_MS)
        image(canvas, images[index], bounds, motion, index, 255)
        if (images.size > 1 && age > SLIDE_MS - FADE_MS) {
            val nextAge = age - (SLIDE_MS - FADE_MS)
            val next = (index + 1) % images.size
            image(canvas, images[next], bounds, nextAge.toFloat() / (SLIDE_MS + FADE_MS), next,
                (255 * nextAge / FADE_MS).toInt())
        }
    }

    private fun image(canvas: Canvas, bitmap: Bitmap, bounds: RectF, motion: Float, index: Int, alpha: Int) {
        val scale = maxOf(bounds.width() / bitmap.width, bounds.height() / bitmap.height) * (1.04f + motion * 0.12f)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val pan = if (index % 2 == 0) motion else 1f - motion
        val left = bounds.left - (width - bounds.width()) * lerp(0.35f, 0.65f, pan)
        val top = bounds.top - (height - bounds.height()) * lerp(0.4f, 0.6f, pan)
        destination.set(left, top, left + width, top + height)
        paint.alpha = alpha
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    private companion object {
        const val CHANGE_FADE_MS = 1000L
        const val SLIDE_MS = 10000L
        const val FADE_MS = 1500L
    }
}
