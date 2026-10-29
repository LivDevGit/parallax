package com.parallax.launcher

import android.content.Context

/**
 * The name shown in the greeting. Android TV profiles are usually just "Owner", so it's set explicitly:
 * `adb shell am start -n com.parallax.launcher/.MainActivity --es name "Your name"`
 */
object UserName {

    private const val PREFS = "user"
    private const val KEY_NAME = "name"

    fun get(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun set(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NAME, name.trim()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
