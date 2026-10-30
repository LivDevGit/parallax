package com.parallax.launcher

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * A slowly drifting dark gradient for the resting home screen, like an iOS wallpaper.
 *
 * Soft colour blobs are rendered on the CPU into a tiny bitmap and stretched over the screen with smooth
 * filtering, so the GPU only draws one textured rectangle per frame. Read [frame] while drawing [image]
 * so the drawing is redone each time the bitmap changes.
 */
class AnimatedGradient {

    private val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(WIDTH * HEIGHT)
    val image: ImageBitmap = bitmap.asImageBitmap()

    var frame by mutableIntStateOf(0)
        private set

    private class Blob(
        val r: Float, val g: Float, val b: Float,
        val radius: Float,
        val cx: Float, val cy: Float,
        val ax: Float, val ay: Float,
        // Seconds per full loop; mismatched periods keep the motion from visibly repeating.
        val periodX: Float, val periodY: Float,
    )

    private val blobs = listOf(
        Blob(0x1E / 255f, 0x2A / 255f, 0x78 / 255f, 0.75f, 0.25f, 0.30f, 0.20f, 0.18f, 47f, 61f), // indigo
        Blob(0x4B / 255f, 0x1D / 255f, 0x6B / 255f, 0.70f, 0.75f, 0.25f, 0.22f, 0.20f, 53f, 43f), // violet
        Blob(0x0D / 255f, 0x5C / 255f, 0x63 / 255f, 0.65f, 0.60f, 0.80f, 0.25f, 0.15f, 67f, 71f), // teal
        Blob(0x5A / 255f, 0x14 / 255f, 0x30 / 255f, 0.55f, 0.15f, 0.85f, 0.18f, 0.22f, 59f, 37f), // wine
    )

    // Per-render blob positions, so the pixel loop does no trig and allocates nothing.
    private val blobX = FloatArray(blobs.size)
    private val blobY = FloatArray(blobs.size)
    private val blobInvR2 = FloatArray(blobs.size) { 1f / (blobs[it].radius * blobs[it].radius) }

    fun render(seconds: Float) {
        val aspect = WIDTH.toFloat() / HEIGHT
        for (i in blobs.indices) {
            val blob = blobs[i]
            blobX[i] = (blob.cx + blob.ax * sin(seconds * TWO_PI / blob.periodX)) * aspect
            blobY[i] = blob.cy + blob.ay * cos(seconds * TWO_PI / blob.periodY)
        }
        for (y in 0 until HEIGHT) {
            val fy = y / (HEIGHT - 1f)
            for (x in 0 until WIDTH) {
                val fx = x / (WIDTH - 1f) * aspect
                var r = BASE_R
                var g = BASE_G
                var b = BASE_B
                for (i in blobs.indices) {
                    val dx = fx - blobX[i]
                    val dy = fy - blobY[i]
                    val falloff = (1f - (dx * dx + dy * dy) * blobInvR2[i]).coerceAtLeast(0f)
                    if (falloff == 0f) continue
                    val weight = falloff * falloff
                    val blob = blobs[i]
                    r += blob.r * weight
                    g += blob.g * weight
                    b += blob.b * weight
                }
                pixels[y * WIDTH + x] = (0xFF shl 24) or
                    ((r.coerceAtMost(1f) * 255).toInt() shl 16) or
                    ((g.coerceAtMost(1f) * 255).toInt() shl 8) or
                    (b.coerceAtMost(1f) * 255).toInt()
            }
        }
    }

    /** Copies the latest render into the bitmap. Call on the main thread, where drawing happens. */
    fun publish() {
        bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        frame++
    }

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 90
        const val TWO_PI = (2 * Math.PI).toFloat()
        const val BASE_R = 0x06 / 255f
        const val BASE_G = 0x06 / 255f
        const val BASE_B = 0x0A / 255f
    }
}

/** An [AnimatedGradient] that moves while [running], and freezes when the launcher isn't on screen. */
@Composable
fun rememberAnimatedGradient(running: Boolean): AnimatedGradient {
    val gradient = remember { AnimatedGradient().apply { render(0f); publish() } }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = System.nanoTime()
        while (true) {
            // Ten updates a second is plenty for motion this slow: each step shifts a colour by about one
            // level in 255. Waiting for a frame also pauses the loop entirely while another app is in front,
            // since no frames are drawn then.
            delay(100)
            withFrameNanos { }
            val seconds = (System.nanoTime() - start) / 1e9f
            withContext(Dispatchers.Default) { gradient.render(seconds) }
            gradient.publish()
        }
    }
    return gradient
}
