package com.co2.wifiautoswitch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * On Android 13+ (API 33), holding NEARBY_WIFI_DEVICES unlocks real Wi-Fi SSID/BSSID info via a
 * persistently registered ConnectivityManager NetworkCallback, independent of the system Location
 * toggle. This requires the manifest to declare usesPermissionFlags="neverForLocation" on this
 * permission — without that flag, NEARBY_WIFI_DEVICES is treated the same as ACCESS_FINE_LOCATION
 * and stays gated behind the Location toggle too, defeating the point. A one-off
 * getNetworkCapabilities() query returns redacted WifiInfo regardless of permissions — only a
 * registered callback's delivered onCapabilitiesChanged reliably carries the real data, which is
 * why callers cache that instead of querying fresh each time.
 */
fun hasNearbyWifiDevicesAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
        PackageManager.PERMISSION_GRANTED
}
