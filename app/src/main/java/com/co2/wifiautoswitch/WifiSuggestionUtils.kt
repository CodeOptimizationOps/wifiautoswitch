package com.co2.wifiautoswitch

import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.util.Log

private const val TAG = "WifiAutoSwitchDebug"

/**
 * Removes Wi-Fi network suggestions, tolerating the case where one or more are already gone —
 * e.g. deleted from the Manage Networks screen after the monitoring service already pruned it,
 * or never successfully added in the first place. Never throws; always safe to call.
 */
fun removeSuggestionsGracefully(wifiManager: WifiManager, suggestions: List<WifiNetworkSuggestion>): Int {
    if (suggestions.isEmpty()) return WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
    return try {
        val status = wifiManager.removeNetworkSuggestions(suggestions)
        when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                Log.d(TAG, "removeSuggestionsGracefully: removed ${suggestions.size} suggestion(s)")
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID ->
                Log.d(TAG, "removeSuggestionsGracefully: already not suggested, nothing to do")
            else ->
                Log.d(TAG, "removeSuggestionsGracefully: unexpected status=$status")
        }
        status
    } catch (e: SecurityException) {
        Log.d(TAG, "removeSuggestionsGracefully: permission unavailable, ignoring: ${e.message}")
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL
    }
}
