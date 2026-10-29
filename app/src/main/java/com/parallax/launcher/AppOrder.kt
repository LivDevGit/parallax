package com.parallax.launcher

import android.content.Context

/** Remembers the order the user arranged their apps in. Apps installed later go to the end, alphabetically. */
object AppOrder {

    private const val PREFS = "layout"
    private const val KEY_ORDER = "order"

    fun apply(context: Context, apps: List<AppInfo>): List<AppInfo> {
        val saved = prefs(context).getString(KEY_ORDER, null) ?: return apps
        val rank = saved.split('\n').withIndex().associate { it.value to it.index }
        // sortedBy is stable, so unranked apps keep their alphabetical order.
        return apps.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    fun save(context: Context, apps: List<AppInfo>) {
        prefs(context).edit().putString(KEY_ORDER, apps.joinToString("\n") { it.id }).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
