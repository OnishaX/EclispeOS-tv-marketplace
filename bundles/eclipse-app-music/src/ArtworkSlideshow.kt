package com.example.eclipse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.cos

class ArtworkSlideshow(images: List<Bitmap>, private val startedAt: Long = SystemClock.uptimeMillis()) {
    private val images = images.toList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    init { require(images.isNotEmpty()) { "A slideshow needs at least one image." } }

    fun draw(canvas: Canvas, bounds: RectF, alpha: Float = 1f, now: Long = SystemClock.uptimeMillis()) {
        val elapsed = (now - startedAt).coerceAtLeast(0)
        val frame = elapsed / SLIDE_MS
        val age = elapsed % SLIDE_MS
        val index = (frame % images.size).toInt()
        val saved = canvas.save()
        canvas.clipRect(bounds)
        if (alpha < 1f) canvas.saveLayerAlpha(bounds, (alpha.coerceIn(0f, 1f) * 255).toInt())
        val motion = if (images.size == 1) ((1 - cos(elapsed * Math.PI / (SLIDE_MS + FADE_MS))) / 2).toFloat()
            else (age + FADE_MS).toFloat() / (SLIDE_MS + FADE_MS)
        image(canvas, images[index], bounds, motion, index, 255)
        if (images.size > 1 && age > SLIDE_MS - FADE_MS) {
            val nextAge = age - (SLIDE_MS - FADE_MS)
            val next = (index + 1) % images.size
            image(canvas, images[next], bounds, nextAge.toFloat() / (SLIDE_MS + FADE_MS), next,
                (255 * nextAge / FADE_MS).toInt())
        }
        canvas.restoreToCount(saved)
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
        const val SLIDE_MS = 10000L
        const val FADE_MS = 1500L
    }
}
