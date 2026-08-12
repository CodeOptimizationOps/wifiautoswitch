package com.co2.wifiautoswitch

import android.net.wifi.WifiNetworkSuggestion

enum class WifiSecurityType {
    WPA2,
    WPA3,
    OPEN
}

data class SavedWifiNetwork(
    val ssid: String,
    val password: String,
    val securityType: WifiSecurityType
)

fun SavedWifiNetwork.toSuggestion(): WifiNetworkSuggestion {
    val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
    when (securityType) {
        WifiSecurityType.WPA2 -> builder.setWpa2Passphrase(password)
        WifiSecurityType.WPA3 -> builder.setWpa3Passphrase(password)
        WifiSecurityType.OPEN -> { /* no passphrase needed */ }
    }
    return builder.build()
}
