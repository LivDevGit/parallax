package com.parallax.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val id: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    /** A tiny version of the banner, stretched into the blurred Top Shelf backdrop. */
    val backdrop: ImageBitmap?,
    val launchIntent: Intent,
)

object AppRepository {

    suspend fun load(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val tvApps = query(pm, Intent.CATEGORY_LEANBACK_LAUNCHER)
        val otherApps = query(pm, Intent.CATEGORY_LAUNCHER)

        val apps = (tvApps + otherApps)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { it.toAppInfo(pm) }
            .sortedBy { it.label.lowercase() }

        // Google TV hides Settings from the app list, but a launcher has to offer a way in.
        val settings = pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
            ?.toAppInfo(pm, Intent(Settings.ACTION_SETTINGS))

        (apps + listOfNotNull(settings)).distinctBy { it.id }
    }

    private fun query(pm: PackageManager, category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    private fun ResolveInfo.toAppInfo(pm: PackageManager, intent: Intent? = null): AppInfo {
        val info = activityInfo
        val banner = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm)
        val launch = intent
            ?: pm.getLeanbackLaunchIntentForPackage(info.packageName)
            ?: pm.getLaunchIntentForPackage(info.packageName)
            ?: Intent(Intent.ACTION_MAIN).setClassName(info.packageName, info.name)
        val bannerBitmap = banner?.toBitmap(320, 180)
        val iconBitmap = loadIcon(pm)?.toBitmap(192, 192)
        return AppInfo(
            id = info.packageName,
            label = loadLabel(pm).toString(),
            banner = bannerBitmap?.asImageBitmap(),
            icon = iconBitmap?.asImageBitmap(),
            backdrop = (bannerBitmap ?: iconBitmap)?.let { blurredBackdrop(it).asImageBitmap() },
            launchIntent = launch,
        )
    }

    /**
     * Blurs once on the CPU at a small size, so the Top Shelf costs nothing per frame.
     * Three box-blur passes approximate a gaussian; stretched to full screen it stays smooth.
     */
    private fun blurredBackdrop(source: Bitmap): Bitmap {
        val w = 64
        val h = 36
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) {
            boxBlur(pixels, w, h, radius = 4, horizontal = true)
            boxBlur(pixels, w, h, radius = 4, horizontal = false)
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
