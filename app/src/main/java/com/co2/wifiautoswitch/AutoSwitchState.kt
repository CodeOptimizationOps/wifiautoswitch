package com.co2.wifiautoswitch

import android.content.Context

/** Persisted "is auto-switch enabled" flag, shared between MainActivity and WifiAutoSwitchService. */
object AutoSwitchState {
    private const val PREFS_NAME = "wifi_auto_switch_state"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
