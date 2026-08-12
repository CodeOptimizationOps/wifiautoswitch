package com.co2.wifiautoswitch

import android.content.Context

/**
 * Tracks SSIDs this device has actually connected to while the service was running but that
 * aren't yet in [NetworkCredentialStore]. There is no API for a normal app to read the phone's
 * existing saved-network list (see NetworkCredentialStore), so this can only accumulate going
 * forward from live connections, not backfill history from before the app was installed.
 */
class ObservedNetworkStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    fun getObserved(): Set<String> = prefs.getStringSet(KEY_OBSERVED, emptySet()) ?: emptySet()

    fun recordObserved(ssid: String) {
        val updated = getObserved().toMutableSet()
        if (updated.add(ssid)) {
            prefs.edit().putStringSet(KEY_OBSERVED, updated).apply()
        }
    }

    fun remove(ssid: String) {
        val updated = getObserved().toMutableSet()
        if (updated.remove(ssid)) {
            prefs.edit().putStringSet(KEY_OBSERVED, updated).apply()
        }
    }

    /**
     * The most recent SSID the service actually read from the live connection (managed or not).
     * Reading Wi-Fi info directly from the Activity is unreliable — Android only reliably hands
     * back non-redacted WifiInfo (real SSID/networkId) in this app's Service context, not here,
     * even with an identical registered NetworkCallback and all permissions granted. So the
     * Activity reads the Service's last observation instead of querying Wi-Fi info itself.
     */
    fun getLastConnectedSsid(): String? = prefs.getString(KEY_LAST_CONNECTED_SSID, null)

    fun recordLastConnectedSsid(ssid: String) {
        prefs.edit().putString(KEY_LAST_CONNECTED_SSID, ssid).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "wifi_auto_switch_observed_networks"
        private const val KEY_OBSERVED = "observed_ssids"
        private const val KEY_LAST_CONNECTED_SSID = "last_connected_ssid"
    }
}
