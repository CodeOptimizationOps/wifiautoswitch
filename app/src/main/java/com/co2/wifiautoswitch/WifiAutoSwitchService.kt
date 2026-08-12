package com.co2.wifiautoswitch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "WifiAutoSwitchDebug"

class WifiAutoSwitchService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var credentialStore: NetworkCredentialStore
    private lateinit var observedNetworkStore: ObservedNetworkStore
    private lateinit var locationManager: LocationManager
    private var locationModeReceiver: BroadcastReceiver? = null
    private var wasLocationEnabled: Boolean = true
    private var wifiStateReceiver: BroadcastReceiver? = null
    private lateinit var wifiEnabledFlow: MutableStateFlow<Boolean>
    private var scanResultsReceiver: BroadcastReceiver? = null
    private val scanResultsSignal = Channel<Unit>(Channel.CONFLATED)
    private var currentNetworkCallback: ConnectivityManager.NetworkCallback? = null
    // A one-off getNetworkCapabilities() query returns redacted WifiInfo regardless of
    // permissions — only a persistently registered callback's delivered onCapabilitiesChanged
    // reliably carries the real data, which is what this cache holds.
    @Volatile private var cachedWifiInfo: android.net.wifi.WifiInfo? = null
    private var lastCheckTime: Long = 0
    // Floor between checks, whether triggered by the timer or the RSSI-change callback — keeps
    // scanning within Android's background scan throttling budget regardless of trigger source.
    // Only applies when Location is on (the scan-based path); the Location-off path does no
    // scanning at all, so it reacts immediately with no floor — see maybeEvaluateCurrentNetwork.
    private val scanBasedMinCheckIntervalMs: Long = 30_000
    // Only switch when a saved network is meaningfully stronger, not just marginally better —
    // avoids flapping between networks with similar signal.
    private val switchMarginDb = 18
    // Location-off fallback only kicks in once the connection is actually poor — we can't do the
    // scan-based comparison without Location, so this threshold gates a blunter reset instead.
    private val poorThresholdRssi = -75
    // Edge-trigger for the location-off reset: true once a reset has fired for the current poor
    // streak, so we don't keep resetting every cycle when every saved network is simply weak here
    // (nothing to gain from repeating it). Cleared the moment RSSI recovers above threshold, which
    // re-arms the next drop as a fresh trigger.
    private var resetFiredForCurrentPoorStreak = false

    // SSIDs this service currently believes are active as Wi-Fi network suggestions with the OS.
    private val activeSuggestedSsids = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        credentialStore = NetworkCredentialStore(applicationContext)
        observedNetworkStore = ObservedNetworkStore(applicationContext)
        locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wasLocationEnabled = locationManager.isLocationEnabled
        wifiEnabledFlow = MutableStateFlow(wifiManager.isWifiEnabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        registerConnectivityCallback()
        registerLocationModeReceiver()
        registerWifiStateReceiver()
        registerScanResultsReceiver()
        suggestAllSavedNetworks()
        serviceScope.launch { monitorCurrentNetwork() }
    }

    // Without this, the default START_STICKY tells Android to silently recreate this service
    // after the process is killed in the background (e.g. memory pressure while screen-locked).
    // That recreation happens outside any user-visible/foreground-exempt context, so the
    // unconditional startForeground() call in onCreate() throws
    // ForegroundServiceStartNotAllowedException immediately — crashing, getting restarted, and
    // crashing again in a loop. The app already restarts this service explicitly whenever it's
    // actually needed (enable toggle, Wi-Fi turning back on), so an OS-driven silent respawn is
    // never wanted.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        currentNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        locationModeReceiver?.let { unregisterReceiver(it) }
        wifiStateReceiver?.let { unregisterReceiver(it) }
        scanResultsReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    /**
     * Signals scanAndPruneToClosestNetwork the instant scan results are actually ready, instead
     * of guessing with a fixed delay — startScan() is fire-and-forget and gives no way to know
     * how long a scan will actually take.
     */
    private fun registerScanResultsReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scanResultsSignal.trySend(Unit)
            }
        }
        scanResultsReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Wi-Fi being manually turned off is a routine, often brief user action (unlike Location) —
     * we don't disable auto-switch for it, just pause the timer loop with zero wakeups while off,
     * resuming the instant Wi-Fi is back on. See maybeEvaluateCurrentNetwork's timer half.
     */
    private fun registerWifiStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                val isEnabled = state == WifiManager.WIFI_STATE_ENABLED
                Log.d(TAG, "wifi state changed: enabled=$isEnabled")
                wifiEnabledFlow.value = isEnabled
            }
        }
        wifiStateReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun registerLocationModeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val isEnabled = locationManager.isLocationEnabled
                Log.d(TAG, "location mode changed: enabled=$isEnabled")
                if (wasLocationEnabled && !isEnabled) {
                    // Location off no longer disables auto-switch — it's an accuracy
                    // recommendation, not a hard requirement. See evaluateCurrentNetwork's
                    // location-off fallback path.
                    postLocationDisabledAlert()
                }
                wasLocationEnabled = isEnabled
            }
        }
        locationModeReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun postLocationDisabledAlert() {
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Wi-Fi Auto Switch alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Warns when Location is turned off, since auto-switch can't work without it."
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("Location is off")
            .setContentText(
                "For more accurate network switching, it's recommended to turn Location back " +
                    "on. Auto-switch still works without it. Tap to enable it."
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager?.notify(LOCATION_ALERT_NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun registerConnectivityCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        // FLAG_INCLUDE_LOCATION_INFO (API 31+) is required for onCapabilitiesChanged to deliver
        // non-redacted WifiInfo — without it, delivered capabilities are redacted by default
        // regardless of what permissions the app holds.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        } else {
            0
        }
        currentNetworkCallback = object : ConnectivityManager.NetworkCallback(flags) {
            override fun onAvailable(network: android.net.Network) {
                serviceScope.launch { maybeEvaluateCurrentNetwork() }
            }

            override fun onLost(network: android.net.Network) {
                serviceScope.launch { maybeEvaluateCurrentNetwork() }
            }

            // Fires whenever the connected Wi-Fi network's signal strength (or other
            // capabilities) changes — the event-driven half of the hybrid check. Still passes
            // through the same rate limit as the timer, so it can't cause excessive scanning.
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                capabilities: NetworkCapabilities
            ) {
                cachedWifiInfo = capabilities.transportInfo as? android.net.wifi.WifiInfo
                Log.d("WifiAutoSwitchDebug",
                    "Service onCapabilitiesChanged: flags=$flags transportInfo=${capabilities.transportInfo} " +
                        "ssid=${cachedWifiInfo?.ssid} networkId=${cachedWifiInfo?.networkId} " +
                        "hasNearby=${hasNearbyWifiDevicesAccess(this@WifiAutoSwitchService)}")
                serviceScope.launch { maybeEvaluateCurrentNetwork() }
            }
        }

        connectivityManager.registerNetworkCallback(request, currentNetworkCallback!!)
    }

    private fun suggestAllSavedNetworks() {
        val savedNetworks = credentialStore.getAll()
        if (savedNetworks.isEmpty()) return

        val suggestions = savedNetworks.map { it.toSuggestion() }
        val status = wifiManager.addNetworkSuggestions(suggestions)
        Log.d(TAG, "suggestAllSavedNetworks: added ${suggestions.size} suggestions, status=$status")
        activeSuggestedSsids.clear()
        activeSuggestedSsids.addAll(savedNetworks.map { it.ssid })
    }

    /**
     * Timed fallback half of the hybrid check — guarantees a periodic look-around even when the
     * connection is stable enough that no RSSI-change callback ever fires (e.g. a much stronger
     * saved network newly in range that doesn't affect the current connection's own signal).
     * Suspends with zero wakeups while Wi-Fi is off (wifiEnabledFlow.first{it} doesn't poll —
     * it resumes the instant the WIFI_STATE_CHANGED_ACTION receiver flips the flow to true).
     */
    private suspend fun monitorCurrentNetwork() {
        while (true) {
            wifiEnabledFlow.first { it }
            maybeEvaluateCurrentNetwork()
            delay(5_000)
        }
    }

    /**
     * Shared entry point for both trigger paths (the timer and the RSSI-change callback). With
     * Location on, evaluation does an active scan, so it's capped to once per
     * scanBasedMinCheckIntervalMs to stay within Android's background scan throttling budget.
     * With Location off, evaluation only reads the current connection's RSSI (no scan), so there's
     * no throttling budget to protect — it reacts immediately on every trigger.
     */
    private suspend fun maybeEvaluateCurrentNetwork() {
        val now = System.currentTimeMillis()
        val locationOn = locationManager.isLocationEnabled
        if (!locationOn || now - lastCheckTime >= scanBasedMinCheckIntervalMs) {
            evaluateCurrentNetwork()
            lastCheckTime = now
        }
    }

    /**
     * Location gates SSID/BSSID/networkId (redacted to -1 / "<unknown ssid>" when off) but not
     * RSSI itself — we saw real RSSI values come through even with Location off. So networkId
     * can't be used to detect "connected to Wi-Fi" when Location is off; ConnectivityManager's
     * transport check isn't location-gated and works either way.
     */
    private fun isConnectedToWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
    }

    /** Modern path first (works regardless of Location on API 33+), reading from cachedWifiInfo —
     *  a one-off getNetworkCapabilities() query returns redacted data regardless of permissions,
     *  only the registered callback's delivered data is reliable. Falls back to the older,
     *  Location-gated WifiManager read otherwise. */
    private fun readCurrentSsidIfAvailable(): String? {
        if (hasNearbyWifiDevicesAccess(this)) {
            val modernSsid = cachedWifiInfo?.ssid?.trim('"')
            if (!modernSsid.isNullOrBlank() && modernSsid != UNKNOWN_SSID) return modernSsid
        }
        if (!locationManager.isLocationEnabled) return null
        return wifiManager.connectionInfo.ssid?.trim('"')
    }

    private suspend fun evaluateCurrentNetwork() {
        withContext(Dispatchers.Default) {
            if (!isConnectedToWifi()) return@withContext

            val rssi = wifiManager.connectionInfo.rssi
            val currentSsid = readCurrentSsidIfAvailable()
            Log.d(TAG, "evaluateCurrentNetwork: ssid=$currentSsid rssi=$rssi")

            if (currentSsid != null && currentSsid.isNotBlank() && currentSsid != UNKNOWN_SSID) {
                observedNetworkStore.recordLastConnectedSsid(currentSsid)
                val isManaged = credentialStore.getAll().any { it.ssid == currentSsid }
                if (!isManaged) {
                    observedNetworkStore.recordObserved(currentSsid)
                }
            }

            // Active scanning (startScan()/getScanResults()) stays gated on Location specifically,
            // regardless of API level or NEARBY_WIFI_DEVICES — unlike the current connection's
            // SSID above, scan results reveal nearby APs you haven't joined, which Android treats
            // as more sensitive and doesn't relax for this permission.
            if (locationManager.isLocationEnabled) {
                // Can read real SSIDs from scan results, so do the precise thing: compare
                // signal strength per saved network and narrow suggestions to the strongest.
                scanAndPruneToClosestNetwork(currentRssi = rssi, currentSsid = currentSsid)
            } else {
                // Can't match scan results to SSIDs without Location, so there's no way to know
                // which saved network is actually strongest nearby. Fall back to a blunter move:
                // if the connection is poor, reset suggestions and let the OS's own roaming logic
                // pick among the others — but still exclude the one we know is currently weak
                // (currentSsid, from the modern API above), so the OS isn't just handed back the
                // very network we're trying to get away from.
                if (rssi < poorThresholdRssi) {
                    // Only fire once per poor streak — if every saved network is genuinely weak
                    // here, resetting again on every cycle just churns suggestions for no gain.
                    if (!resetFiredForCurrentPoorStreak) {
                        resetAllSuggestionsTogether(excludingSsid = currentSsid)
                        resetFiredForCurrentPoorStreak = true
                    }
                } else {
                    // Recovered above threshold — re-arm so the next drop triggers a fresh reset.
                    resetFiredForCurrentPoorStreak = false
                }
            }
        }
    }

    /**
     * Location-off fallback: removes every saved network's suggestion, then re-adds all of them
     * *except* the one we know is currently weak (excludingSsid, read via the modern API above),
     * without trying to pick a "best" one among the rest — we can't match scan results to real
     * SSIDs without Location, so we hand that decision to the OS's own network selection instead.
     * If excludingSsid is null (modern API unavailable, e.g. API < 33) we have no basis to exclude
     * anything and just re-add everything, same as before.
     */
    private fun resetAllSuggestionsTogether(excludingSsid: String?) {
        val savedNetworks = credentialStore.getAll()
        if (savedNetworks.isEmpty()) return

        removeSuggestionsGracefully(wifiManager, savedNetworks.map { it.toSuggestion() })

        val networksToReadd = savedNetworks.filterNot { it.ssid == excludingSsid }
        if (networksToReadd.isEmpty()) {
            // The only saved network is the current, weak one — nothing better to fall back to,
            // so leave it removed rather than re-adding the very network we're trying to avoid.
            activeSuggestedSsids.clear()
            Log.d(TAG, "resetAllSuggestionsTogether: only saved network is the weak current one, none re-added")
            return
        }

        val addStatus = wifiManager.addNetworkSuggestions(networksToReadd.map { it.toSuggestion() })
        Log.d(
            TAG,
            "resetAllSuggestionsTogether: re-added ${networksToReadd.size} suggestions " +
                "(excluded current=$excludingSsid), status=$addStatus"
        )

        activeSuggestedSsids.clear()
        activeSuggestedSsids.addAll(networksToReadd.map { it.ssid })
    }

    /**
     * Scan, then narrow the active Wi-Fi suggestions down to whichever saved network currently
     * has the strongest signal, so the OS is nudged toward roaming to it — evaluated every check
     * regardless of whether the current connection is "poor," so a much stronger saved network
     * gets picked up even if the current one is merely adequate. There is no API to force an
     * immediate connection to a suggested network — this only biases the system's own roaming
     * decision.
     */
    private suspend fun scanAndPruneToClosestNetwork(currentRssi: Int, currentSsid: String?) {
        if (!wifiManager.isWifiEnabled) return

        // Drain any stale signal from an earlier, unrelated scan before starting ours, so the
        // wait below can't return instantly on a leftover event that isn't for this scan.
        scanResultsSignal.tryReceive()

        val scanStarted = wifiManager.startScan()
        Log.d(TAG, "startScan() returned $scanStarted")
        if (!scanStarted) return

        val gotResults = withTimeoutOrNull(SCAN_RESULTS_TIMEOUT_MS) { scanResultsSignal.receive() } != null
        Log.d(TAG, "scan results ${if (gotResults) "arrived" else "timed out, using best-effort data"}")

        val scanResults = wifiManager.scanResults
        val savedNetworks = credentialStore.getAll()
        val savedSsids = savedNetworks.map { it.ssid }.toSet()
        Log.d(TAG, "scanResults size=${scanResults.size} savedNetworks size=${savedNetworks.size}")

        val bestSavedResult = scanResults
            .filter { it.SSID.isNotBlank() && it.SSID in savedSsids }
            .maxByOrNull { it.level }
            ?: return

        if (bestSavedResult.SSID == currentSsid) return
        if (bestSavedResult.level <= currentRssi + switchMarginDb) return

        val bestNetwork = savedNetworks.first { it.ssid == bestSavedResult.SSID }
        val toRemove = activeSuggestedSsids
            .filterNot { it == bestNetwork.ssid }
            .mapNotNull { ssid -> savedNetworks.find { it.ssid == ssid }?.toSuggestion() }

        removeSuggestionsGracefully(wifiManager, toRemove)

        val addStatus = wifiManager.addNetworkSuggestions(listOf(bestNetwork.toSuggestion()))
        Log.d(TAG, "addNetworkSuggestions: ensured ${bestNetwork.ssid} active, status=$addStatus")

        activeSuggestedSsids.clear()
        activeSuggestedSsids.add(bestNetwork.ssid)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "wifi_auto_switch_channel"
        private const val NOTIFICATION_ID = 101
        private const val ALERT_CHANNEL_ID = "wifi_auto_switch_alerts"
        private const val LOCATION_ALERT_NOTIFICATION_ID = 102
        private const val UNKNOWN_SSID = "<unknown ssid>"
        // Safety cap in case a scan silently never completes — otherwise we'd wait forever.
        private const val SCAN_RESULTS_TIMEOUT_MS = 6_000L
    }
}
