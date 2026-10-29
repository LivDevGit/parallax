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
            backdrop = (bannerBitmap ?: iconBitmap)?.let { blurredBackdrop(it).also(Bitmap::prepareToDraw).asImageBitmap() },
            launchIntent = launch,
        )
    }

    /** A small, pre-blurred copy of the banner for the Top Shelf backdrop. */
    private fun blurredBackdrop(source: Bitmap): Bitmap =
        Precomputed.blur(Bitmap.createScaledBitmap(source, 64, 36, true), radius = 4)
}
