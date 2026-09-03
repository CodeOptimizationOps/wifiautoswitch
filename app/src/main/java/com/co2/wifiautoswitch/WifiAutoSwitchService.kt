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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val switchMarginDb = 10
    // When Location is on, scanAndPruneToClosestNetwork narrows suggestions down to just the
    // best network, removing everyone else — unlike the Location-off path, nothing restored them
    // afterward. Mirrors the same fix: unconditionally re-add everyone this many ms after a
    // narrowing, regardless of what happens in between, since the switch itself normally
    // completes in a few seconds — restoring competitors after that is safe.
    private val narrowReincludeDelayMs = 15_000L
    private var narrowedAwayAtMs: Long? = null
    // Location-off fallback's decision logic (Scenarios 1 and 2 — out of range of everything, and
    // connected-but-weak) — extracted into its own class so it's unit-testable independent of
    // WifiManager/ConnectivityManager. See WeakSignalRecoveryPolicy for the full behavior.
    private val weakSignalRecoveryPolicy = WeakSignalRecoveryPolicy()

    // SSIDs this service currently believes are active as Wi-Fi network suggestions with the OS.
    private val activeSuggestedSsids = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
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
        isRunning = false
        currentNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        locationModeReceiver?.let { unregisterReceiver(it) }
        wifiStateReceiver?.let { unregisterReceiver(it) }
        scanResultsReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    /**
     * Reacts whenever scan results become available from ANY source — our own startScan() call,
     * another app's, or Android's own periodic background scanning (roaming evaluation, PNO,
     * etc., which happens regardless of what we request). Android's scan-throttling budget only
     * limits how often *we* can call startScan() ourselves; passively reading whatever results
     * already exist whenever this fires costs nothing against that budget, and the system scans
     * far more often on its own than our throttled requests alone would allow — so this is how a
     * newly-appeared strong network actually gets noticed quickly in practice.
     */
    private fun registerScanResultsReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                serviceScope.launch { reactToFreshScanResultsIfApplicable() }
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

    private suspend fun reactToFreshScanResultsIfApplicable() {
        if (!locationManager.isLocationEnabled) return
        if (!isConnectedToWifi()) return
        val rssi = wifiManager.connectionInfo.rssi
        val currentSsid = readCurrentSsidIfAvailable()
        withContext(Dispatchers.Default) {
            pruneToClosestNetworkFromScanResults(currentRssi = rssi, currentSsid = currentSsid)
        }
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
            // Checking the clock costs nothing against Android's scan-throttling budget — only
            // an actual startScan() call does. A tight tick here just minimizes how much slop
            // gets added on top of scanBasedMinCheckIntervalMs once that floor clears.
            delay(1_000)
        }
    }

    /**
     * Shared entry point for both trigger paths (the timer and the RSSI-change callback). With
     * Location on, evaluation does an active scan, so it's capped to once per
     * scanBasedMinCheckIntervalMs to stay within Android's background scan throttling budget —
     * that ceiling is a hard OS-enforced average (4 scans per rolling 2-minute window for a
     * foreground app, far stricter when backgrounded), not something safe to scan faster than
     * sustained, regardless of app state. With Location off, evaluation only reads the current
     * connection's RSSI (no scan), so there's no throttling budget to protect — it reacts
     * immediately on every trigger.
     */
    private suspend fun maybeEvaluateCurrentNetwork() {
        val now = System.currentTimeMillis()
        // Independent of the scan floor below — restoring suggestions isn't a scan, so it isn't
        // subject to the same budget, and shouldn't wait on it either.
        checkPendingNarrowReinclude(now)

        val locationOn = locationManager.isLocationEnabled
        if (!locationOn || now - lastCheckTime >= scanBasedMinCheckIntervalMs) {
            evaluateCurrentNetwork()
            lastCheckTime = now
        }
    }

    private fun checkPendingNarrowReinclude(nowMs: Long) {
        val narrowedAt = narrowedAwayAtMs ?: return
        if (nowMs - narrowedAt < narrowReincludeDelayMs) return
        Log.d(TAG, "Re-including networks narrowed away ${nowMs - narrowedAt}ms ago")
        suggestAllSavedNetworks()
        narrowedAwayAtMs = null
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
            val rssi: Int? = if (isConnectedToWifi()) wifiManager.connectionInfo.rssi else null
            val currentSsid = if (rssi != null) readCurrentSsidIfAvailable() else null

            if (rssi != null) {
                Log.d(TAG, "evaluateCurrentNetwork: ssid=$currentSsid rssi=$rssi")
                if (currentSsid != null && currentSsid.isNotBlank() && currentSsid != UNKNOWN_SSID) {
                    observedNetworkStore.recordLastConnectedSsid(currentSsid)
                    val isManaged = credentialStore.getAll().any { it.ssid == currentSsid }
                    if (!isManaged) {
                        observedNetworkStore.recordObserved(currentSsid)
                    }
                }
            }

            // Active scanning (startScan()/getScanResults()) stays gated on Location specifically,
            // regardless of API level or NEARBY_WIFI_DEVICES — unlike the current connection's
            // SSID above, scan results reveal nearby APs you haven't joined, which Android treats
            // as more sensitive and doesn't relax for this permission.
            if (locationManager.isLocationEnabled) {
                // Can read real SSIDs from scan results, so do the precise thing: compare
                // signal strength per saved network and narrow suggestions to the strongest.
                // This only triggers the scan — the actual decision happens reactively whenever
                // results land (see registerScanResultsReceiver), from this scan or any other.
                if (rssi != null) {
                    triggerScanForClosestNetwork()
                }
                return@withContext
            }

            // Can't match scan results to SSIDs without Location, so there's no way to know which
            // saved network is actually strongest nearby — hand the decision off to
            // WeakSignalRecoveryPolicy's blunter, RSSI-threshold-only approach (Scenarios 1 & 2).
            val action = weakSignalRecoveryPolicy.evaluate(
                isConnected = rssi != null,
                rssi = rssi ?: 0,
                currentSsid = currentSsid,
                nowMs = System.currentTimeMillis()
            )
            when (action) {
                is RecoveryAction.ResetExcluding -> {
                    // Actively retrying again — clear any stale dead-end alert from an earlier
                    // streak, since we haven't given up this time (yet).
                    Log.d(TAG, "WeakSignalRecoveryPolicy: resetting, excluding ${action.ssid}")
                    cancelWeakSignalAlert()
                    resetAllSuggestionsTogether(excludingSsid = action.ssid)
                }
                RecoveryAction.RestoreAll -> {
                    // Silent — no notification yet, this might resolve itself quickly. A plain
                    // add is enough here since it's putting back a genuinely-excluded network.
                    // Also fires on a mid-streak recovery (never fully disconnected), so clear any
                    // stale alert defensively — harmless no-op if nothing was showing.
                    Log.d(TAG, "WeakSignalRecoveryPolicy: restoring all suggestions")
                    cancelWeakSignalAlert()
                    suggestAllSavedNetworks()
                }
                RecoveryAction.LastDitchReset -> {
                    // Still stuck after the first wait — one more nudge before giving up. Needs
                    // an actual remove-then-readd churn, not just an add: everything's already
                    // suggested by this point, so a plain add would be a silent no-op.
                    Log.d(TAG, "WeakSignalRecoveryPolicy: last-ditch remove-all/readd-all")
                    resetAllSuggestionsTogether(excludingSsid = null)
                }
                RecoveryAction.NotifyDeadEnd -> {
                    Log.d(TAG, "WeakSignalRecoveryPolicy: dead-end held, notifying user")
                    postWeakSignalAlert()
                }
                RecoveryAction.None -> {
                    if (rssi != null && rssi >= weakSignalRecoveryPolicy.poorThresholdRssi) {
                        // Clearly fine now — clear any stale dead-end alert.
                        cancelWeakSignalAlert()
                    }
                }
            }
        }
    }

    /**
     * Tapping opens the Wi-Fi quick-settings panel so the user can manually toggle Wi-Fi off/on
     * themselves — apps can't do this programmatically on Android 10+
     * (WifiManager.setWifiEnabled() is restricted to system/DPC apps only), and a manual toggle
     * is a common, effective way to force the OS to redo a full scan/reassociation when it's
     * stuck on a weak connection.
     */
    private fun postWeakSignalAlert() {
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Wi-Fi Auto Switch alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Warns about things auto-switch needs your attention for — Location " +
                "being off, or no strong signal being found among your saved networks."
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(Settings.Panel.ACTION_WIFI),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("No strong Wi-Fi signal found")
            .setContentText(
                "None of your saved networks have a good signal here. Tap to toggle Wi-Fi " +
                    "off and on, which can help it reconnect."
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager?.notify(WEAK_SIGNAL_ALERT_NOTIFICATION_ID, notification)
    }

    private fun cancelWeakSignalAlert() {
        getSystemService(NotificationManager::class.java)?.cancel(WEAK_SIGNAL_ALERT_NOTIFICATION_ID)
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
     * Fire-and-forget: kicks off a scan, then returns immediately. The actual decision runs
     * later, reactively, whenever results actually land — see registerScanResultsReceiver and
     * pruneToClosestNetworkFromScanResults. Since that reacts to results from any source (not
     * just this call), this is mostly a backstop for when nothing else has scanned recently.
     */
    private fun triggerScanForClosestNetwork() {
        if (!wifiManager.isWifiEnabled) return
        val scanStarted = wifiManager.startScan()
        Log.d(TAG, "startScan() returned $scanStarted")
    }

    /**
     * Narrows the active Wi-Fi suggestions down to whichever saved network currently has the
     * strongest signal, so the OS is nudged toward roaming to it — evaluated every time scan
     * results are available, regardless of whether the current connection is "poor," so a much
     * stronger saved network gets picked up even if the current one is merely adequate. There is
     * no API to force an immediate connection to a suggested network — this only biases the
     * system's own roaming decision.
     *
     * This removes every other saved network's suggestion, leaving only the chosen one — see
     * checkPendingNarrowReinclude for how those get restored afterward.
     */
    private fun pruneToClosestNetworkFromScanResults(currentRssi: Int, currentSsid: String?) {
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

        // Make-before-break: add the new suggestion before removing the old ones, so Android
        // sees the better option as available before it loses the current one, rather than
        // dropping the connection first and only then learning what to connect to instead.
        val addStatus = wifiManager.addNetworkSuggestions(listOf(bestNetwork.toSuggestion()))
        Log.d(TAG, "addNetworkSuggestions: ensured ${bestNetwork.ssid} active, status=$addStatus")

        removeSuggestionsGracefully(wifiManager, toRemove)

        activeSuggestedSsids.clear()
        activeSuggestedSsids.add(bestNetwork.ssid)
        narrowedAwayAtMs = System.currentTimeMillis()
    }

    companion object {
        // In-process only, not persisted — deliberately so. A fresh process start (after being
        // killed by force-stop, a reinstall, or the OS reclaiming memory) resets this to false by
        // definition, which is exactly the signal MainActivity needs: the persisted "enabled"
        // preference can outlive the actual running service, and since onStartCommand() returns
        // START_NOT_STICKY on purpose, nothing else will notice or fix that mismatch on its own.
        @Volatile var isRunning: Boolean = false
            private set
        private const val NOTIFICATION_CHANNEL_ID = "wifi_auto_switch_channel"
        private const val NOTIFICATION_ID = 101
        private const val ALERT_CHANNEL_ID = "wifi_auto_switch_alerts"
        private const val LOCATION_ALERT_NOTIFICATION_ID = 102
        private const val WEAK_SIGNAL_ALERT_NOTIFICATION_ID = 103
        private const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
