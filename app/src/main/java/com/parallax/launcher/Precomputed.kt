package com.parallax.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Effects baked into small bitmaps once, on the CPU. This TV's GPU is slow at real shadows and at stacking
 * full-screen gradients, but stretching a tiny bitmap with smooth filtering is nearly free and looks the same.
 */
object Precomputed {

    /** Soft drop shadow for a focused tile. The dark core spans 65% x 52% of the bitmap, centred. */
    val tileShadow: ImageBitmap by lazy {
        val w = 80
        val h = 50
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRoundRect(RectF(14f, 12f, 66f, 38f), 4f, 4f, Paint().apply { color = 0xD0000000.toInt() })
        blur(bitmap, radius = 4).asImageBitmap()
    }

    /**
     * Everything the Top Shelf lays over its artwork in one layer: a darker top behind the clock and greeting,
     * a shaded left side behind the title, and a fade into the background under the app row.
     */
    val shelfOverlay: ImageBitmap by lazy {
        val w = 192
        val h = 108
        val base = 0x0A0A0E
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val fy = y / (h - 1f)
            val vertical = when {
                fy < 0.3f -> 0.35f * (1f - fy / 0.3f)
                fy < 0.5f -> 0f
                fy < 0.82f -> 0.92f * (fy - 0.5f) / 0.32f
                else -> 0.92f + 0.08f * (fy - 0.82f) / 0.18f
            }
            for (x in 0 until w) {
                val fx = x / (w - 1f)
                val side = when {
                    fx < 0.45f -> 0.8f - 0.45f * fx / 0.45f
                    fx < 0.7f -> 0.35f * (1f - (fx - 0.45f) / 0.25f)
                    else -> 0f
                }
                // Stack the two shades the way two translucent layers would.
                val alpha = 1f - (1f - vertical) * (1f - side)
                pixels[y * w + x] = ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or base
            }
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Three box-blur passes in each direction, which approximates a gaussian. Returns a new bitmap. */
    fun blur(source: Bitmap, radius: Int): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) {
            boxBlur(pixels, w, h, radius, horizontal = true)
            boxBlur(pixels, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val line = IntArray(length)
        for (l in 0 until lines) {
            for (i in 0 until length) line[i] = pixels[if (horizontal) l * w + i else i * w + l]
            for (i in 0 until length) {
                var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
                for (k in (i - radius).coerceAtLeast(0)..(i + radius).coerceAtMost(length - 1)) {
                    val c = line[k]
                    a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                pixels[if (horizontal) l * w + i else i * w + l] =
                    ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
