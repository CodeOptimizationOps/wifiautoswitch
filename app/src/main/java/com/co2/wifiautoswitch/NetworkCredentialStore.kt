package com.co2.wifiautoswitch

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class NetworkCredentialStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getAll(): List<SavedWifiNetwork> {
        val raw = prefs.getString(KEY_NETWORKS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            SavedWifiNetwork(
                ssid = obj.getString("ssid"),
                password = obj.optString("password", ""),
                securityType = WifiSecurityType.valueOf(obj.getString("securityType"))
            )
        }
    }

    fun upsert(network: SavedWifiNetwork) {
        val networks = getAll().filterNot { it.ssid == network.ssid } + network
        saveAll(networks)
    }

    fun remove(ssid: String) {
        saveAll(getAll().filterNot { it.ssid == ssid })
    }

    private fun saveAll(networks: List<SavedWifiNetwork>) {
        val array = JSONArray()
        networks.forEach { network ->
            val obj = JSONObject()
            obj.put("ssid", network.ssid)
            obj.put("password", network.password)
            obj.put("securityType", network.securityType.name)
            array.put(obj)
        }
        prefs.edit().putString(KEY_NETWORKS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "wifi_auto_switch_secure_prefs"
        private const val KEY_NETWORKS = "saved_networks"
    }
}
