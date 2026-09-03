package com.co2.wifiautoswitch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.Icon
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

private enum class Screen {
    Main,
    ManageNetworks
}

class MainActivity : ComponentActivity() {
    private lateinit var startServiceIntent: Intent
    private lateinit var credentialStore: NetworkCredentialStore
    private lateinit var observedNetworkStore: ObservedNetworkStore
    private lateinit var locationManager: LocationManager
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    // A one-off getNetworkCapabilities() query returns redacted WifiInfo regardless of
    // permissions — only a persistently registered NetworkCallback's delivered
    // onCapabilitiesChanged reliably carries the real, non-redacted data. So this cache, kept
    // fresh by that callback, is what tryModernSsidRead() actually reads from.
    @Volatile private var cachedWifiInfo: WifiInfo? = null

    // Hoisted to class scope (not just inside setContent's composable) so it survives
    // recomposition and stays in sync with what's actually persisted.
    private var enabledState by mutableStateOf(false)
    // Also class-scoped so onResume() (a plain Activity callback, not a composable) can set it.
    private var promptAddCurrentNetwork by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startServiceIntent = Intent(this, WifiAutoSwitchService::class.java)
        credentialStore = NetworkCredentialStore(applicationContext)
        observedNetworkStore = ObservedNetworkStore(applicationContext)
        locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        enabledState = isAutoSwitchEnabledPersisted()
        registerWifiInfoCallback()

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
                // Without this, granting permissions did nothing visible — the user had to tap
                // Enable a second time for it to actually take effect.
                completeEnable()
            }
        }

        setContent {
            var screen by remember { mutableStateOf(Screen.Main) }
            val enabled = enabledState
            val promptSsid = promptAddCurrentNetwork
            var prefillSsidForManage by remember { mutableStateOf<String?>(null) }
            var locationEnabled by remember { mutableStateOf(locationManager.isLocationEnabled) }

            BackHandler(enabled = screen != Screen.Main) {
                screen = Screen.Main
            }

            DisposableEffect(Unit) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        // Location off no longer disables auto-switch — it's a recommendation
                        // for accuracy, not a hard requirement (see WifiAutoSwitchService, which
                        // falls back to a simpler OS-driven suggestion strategy without it).
                        locationEnabled = locationManager.isLocationEnabled
                    }
                }
                ContextCompat.registerReceiver(
                    this@MainActivity,
                    receiver,
                    IntentFilter(LocationManager.MODE_CHANGED_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                onDispose { unregisterReceiver(receiver) }
            }

            // Catches the moment auto-switch is freshly enabled — onResume() (below) covers
            // every other time the app becomes visible, including while auto-switch is off.
            LaunchedEffect(enabled) {
                if (enabled) {
                    delay(2_500)
                    checkForUnmanagedCurrentNetwork()
                }
            }

            Surface(color = MaterialTheme.colors.background) {
                when (screen) {
                    Screen.Main -> {
                        MainScreen(
                            enabled = enabled,
                            locationEnabled = locationEnabled,
                            onEnableLocation = {
                                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            },
                            onToggle = {
                                if (checkPermissions()) {
                                    if (enabled) {
                                        stopService(startServiceIntent)
                                        enabledState = false
                                        persistAutoSwitchEnabled(false)
                                    } else {
                                        completeEnable()
                                    }
                                } else {
                                    requestPermissionLauncher.launch(getRequiredPermissions())
                                }
                            },
                            onManageNetworks = {
                                prefillSsidForManage = null
                                screen = Screen.ManageNetworks
                            }
                        )

                        promptSsid?.let { ssid ->
                            AlertDialog(
                                onDismissRequest = { promptAddCurrentNetwork = null },
                                title = { Text(text = "Add currently connected network?") },
                                text = {
                                    Text(
                                        text = "This phone is connected to \"$ssid\" but it isn't managed " +
                                            "by this app yet, so auto-switch can't consider it. Add it now?"
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        prefillSsidForManage = ssid
                                        promptAddCurrentNetwork = null
                                        screen = Screen.ManageNetworks
                                    }) {
                                        Text(text = "Add Network")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { promptAddCurrentNetwork = null }) {
                                        Text(text = "Not now")
                                    }
                                }
                            )
                        }
                    }
                    Screen.ManageNetworks -> ManageNetworksScreen(
                        credentialStore = credentialStore,
                        observedNetworkStore = observedNetworkStore,
                        wifiManager = wifiManager,
                        initialSsid = prefillSsidForManage,
                        onBack = { screen = Screen.Main },
                        onForgetNetworkPrompt = {
                            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }
                    )
                }
            }
        }
    }

    private fun completeEnable() {
        ContextCompat.startForegroundService(this, startServiceIntent)
        enabledState = true
        persistAutoSwitchEnabled(true)
    }

    /**
     * A one-off getNetworkCapabilities() query always returns redacted WifiInfo, regardless of
     * permissions — only a persistently registered callback's delivered onCapabilitiesChanged
     * reliably carries real data, AND ONLY if the callback itself is constructed with
     * FLAG_INCLUDE_LOCATION_INFO (API 31+) — without that flag, delivered capabilities are
     * redacted by default regardless of what permissions the app holds. Kept registered for the
     * Activity's whole lifetime so cachedWifiInfo is ready by the time onResume() needs it.
     */
    private fun registerWifiInfoCallback() {
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        } else {
            0
        }
        val callback = object : ConnectivityManager.NetworkCallback(flags) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val info = capabilities.transportInfo as? WifiInfo
                android.util.Log.d(
                    "WifiAutoSwitchDebug",
                    "MainActivity onCapabilitiesChanged: flags=$flags transportInfo=${capabilities.transportInfo} " +
                        "ssid=${info?.ssid} networkId=${info?.networkId}"
                )
                cachedWifiInfo = info
            }

            override fun onLost(network: Network) {
                cachedWifiInfo = null
            }
        }
        wifiNetworkCallback = callback
        connectivityManager.registerNetworkCallback(request, callback)
    }

    override fun onDestroy() {
        wifiNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Covers returning to the app after connecting to Wi-Fi via system Settings, e.g. — the
        // LaunchedEffect above only catches the moment auto-switch gets freshly enabled, not
        // every time the app becomes visible again.

        // Modern path first (Android 13+ with NEARBY_WIFI_DEVICES): reads the real SSID directly,
        // instantly, regardless of the system Location toggle — see WifiInfoUtils.
        val hasAccess = hasNearbyWifiDevicesAccess(this)
        val modernSsid = tryModernSsidRead()
        val classicWifiInfo = wifiManager.connectionInfo
        android.util.Log.d(
            "WifiAutoSwitchDebug",
            "onResume: hasNearbyWifiDevicesAccess=$hasAccess modernSsid=$modernSsid " +
                "classicSsid=${classicWifiInfo.ssid} classicNetworkId=${classicWifiInfo.networkId} " +
                "saved=${credentialStore.getAll().map { it.ssid }}"
        )
        if (modernSsid != null) {
            promptForSsidIfUnmanaged(modernSsid)
            return
        }

        // Fallback path (API < 33, or the permission wasn't granted): needs Location on, since
        // that's the only other way anything can read a real SSID at all — without it,
        // observedNetworkStore's last-known value could be stale from an earlier session (e.g.
        // it kept saying "TP-Link_5EFB_EXT" after switching to "TP-Link_5EFB" purely because
        // nothing was allowed to overwrite it), so skip the check entirely rather than surface a
        // value we can't vouch for as current.
        if (!locationManager.isLocationEnabled) return
        if (enabledState) {
            // Service is continuously running and keeping this fresh.
            observedNetworkStore.getLastConnectedSsid()?.let { promptForSsidIfUnmanaged(it) }
        } else {
            // Store may be stale since the service isn't currently running — get a fresh read.
            probeCurrentNetworkThenCheck()
        }
    }

    private fun tryModernSsidRead(): String? {
        if (!hasNearbyWifiDevicesAccess(this)) return null
        val ssid = cachedWifiInfo?.ssid?.trim('"')
        if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") return null
        return ssid
    }

    private fun promptForSsidIfUnmanaged(ssid: String) {
        if (credentialStore.getAll().none { it.ssid == ssid }) {
            promptAddCurrentNetwork = ssid
        }
    }

    private fun checkForUnmanagedCurrentNetwork() {
        val modernSsid = tryModernSsidRead()
        if (modernSsid != null) {
            promptForSsidIfUnmanaged(modernSsid)
            return
        }
        if (!locationManager.isLocationEnabled) return
        observedNetworkStore.getLastConnectedSsid()?.let { promptForSsidIfUnmanaged(it) }
    }

    /**
     * When auto-switch is off and the modern API isn't available, the service isn't running so
     * observedNetworkStore has no fresh data — the Activity alone can't reliably read the real
     * SSID via the older Location-gated path either (only the Service's
     * foregroundServiceType="location" reliably unlocks that on this device). So briefly start
     * the service just long enough for it to record the current SSID, then stop it again since
     * the user didn't actually ask to enable continuous monitoring.
     */
    private fun probeCurrentNetworkThenCheck() {
        // The service's onCreate() starts a location-typed foreground service, which throws a
        // SecurityException if ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION aren't actually
        // granted yet — unlike the main Enable button, this probe runs from onResume() and can
        // fire before the user has ever gone through the permission flow at all.
        if (!checkPermissions()) return
        ContextCompat.startForegroundService(this, startServiceIntent)
        lifecycleScope.launch {
            delay(2_500)
            if (!enabledState) {
                stopService(startServiceIntent)
            }
            checkForUnmanagedCurrentNetwork()
        }
    }

    private fun checkPermissions(): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun isAutoSwitchEnabledPersisted(): Boolean = AutoSwitchState.isEnabled(applicationContext)

    private fun persistAutoSwitchEnabled(value: Boolean) {
        AutoSwitchState.setEnabled(applicationContext, value)
    }
}

@androidx.compose.runtime.Composable
private fun MainScreen(
    enabled: Boolean,
    locationEnabled: Boolean,
    onEnableLocation: () -> Unit,
    onToggle: () -> Unit,
    onManageNetworks: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "WiFi Auto Switch") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!locationEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFDECEA))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Location is off",
                        style = MaterialTheme.typography.subtitle1,
                        color = Color(0xFFB00020)
                    )
                    Text(
                        text = "For more accurate network switching, it's recommended to turn " +
                            "on Location. Auto-switch still works without it.",
                        style = MaterialTheme.typography.body2,
                        color = Color(0xFFB00020)
                    )
                    Button(onClick = onEnableLocation) {
                        Text(text = "Turn On Location")
                    }
                }
            }

            Text(
                text = if (enabled) "Auto-switch is enabled" else "Auto-switch is disabled",
                style = MaterialTheme.typography.h6
            )

            Button(onClick = onToggle) {
                Text(text = if (enabled) "Disable Auto Switch" else "Enable Auto Switch")
            }

            Button(onClick = onManageNetworks) {
                Text(text = "Manage Wi-Fi Networks")
            }

            Text(text = "The service will keep monitoring network quality in the background.")

            AndroidView(factory = { context ->
                MobileAds.initialize(context) {}
                AdView(context).apply {
                    adUnitId = "ca-app-pub-7082890329942103/4586593717"
                    setAdSize(AdSize.BANNER)
                    loadAd(AdRequest.Builder().build())
                }
            })
        }
    }
}

@androidx.compose.runtime.Composable
private fun ManageNetworksScreen(
    credentialStore: NetworkCredentialStore,
    observedNetworkStore: ObservedNetworkStore,
    wifiManager: WifiManager,
    initialSsid: String?,
    onBack: () -> Unit,
    onForgetNetworkPrompt: () -> Unit
) {
    var networks by remember { mutableStateOf(credentialStore.getAll()) }
    var ssid by remember { mutableStateOf(initialSsid ?: "") }
    var password by remember { mutableStateOf("") }
    var securityType by remember { mutableStateOf(WifiSecurityType.WPA2) }
    var showForgetPrompt by remember { mutableStateOf<String?>(null) }
    var revealedSsids by remember { mutableStateOf(setOf<String>()) }
    var editingNetwork by remember { mutableStateOf<SavedWifiNetwork?>(null) }
    var editSsid by remember { mutableStateOf("") }
    var editPassword by remember { mutableStateOf("") }
    var editSecurityType by remember { mutableStateOf(WifiSecurityType.WPA2) }
    var observedSsids by remember {
        mutableStateOf(
            observedNetworkStore.getObserved() - credentialStore.getAll().map { it.ssid }.toSet()
        )
    }

    fun refreshObserved() {
        observedSsids = observedNetworkStore.getObserved() - credentialStore.getAll().map { it.ssid }.toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Manage Wi-Fi Networks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        // A single LazyColumn for the whole screen (rather than a plain Column with a nested
        // LazyColumn for saved networks — Compose doesn't allow a lazily-measured list inside a
        // non-scrolling parent anyway) so the whole screen scrolls as one unit. Combined with
        // imePadding(), this is what lets the user scroll the password field above the keyboard
        // instead of it being hidden behind it.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Add each Wi-Fi network you want this app to manage. After saving, " +
                        "forget it from your phone's Wi-Fi settings so this app can control it exclusively.",
                    style = MaterialTheme.typography.body2
                )
            }

            if (observedSsids.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Divider()
                        Text(text = "Detected on this device", style = MaterialTheme.typography.subtitle1)
                        Text(
                            text = "This phone connected to these networks but they aren't managed yet. " +
                                "Tap one to add it.",
                            style = MaterialTheme.typography.caption
                        )
                        observedSsids.forEach { observedSsid ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = observedSsid, style = MaterialTheme.typography.body1)
                                Row {
                                    TextButton(onClick = {
                                        ssid = observedSsid
                                        password = ""
                                        securityType = WifiSecurityType.WPA2
                                    }) {
                                        Text(text = "Add")
                                    }
                                    TextButton(onClick = {
                                        observedNetworkStore.remove(observedSsid)
                                        refreshObserved()
                                    }) {
                                        Text(text = "Ignore")
                                    }
                                }
                            }
                        }
                        Divider()
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("Network name (SSID)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = if (securityType == WifiSecurityType.OPEN) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    enabled = securityType != WifiSecurityType.OPEN,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(WifiSecurityType.WPA2, WifiSecurityType.WPA3, WifiSecurityType.OPEN).forEach { type ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = securityType == type,
                                onClick = { securityType = type }
                            )
                            Text(text = type.name)
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        if (ssid.isNotBlank()) {
                            val trimmedSsid = ssid.trim()
                            val newNetwork = SavedWifiNetwork(trimmedSsid, password, securityType)
                            credentialStore.upsert(newNetwork)
                            // Register with the OS immediately — the running service only calls
                            // addNetworkSuggestions() once at startup, so without this the new
                            // network sits in our local store but the OS never learns about it
                            // as a candidate until the service restarts.
                            wifiManager.addNetworkSuggestions(listOf(newNetwork.toSuggestion()))
                            networks = credentialStore.getAll()
                            observedNetworkStore.remove(trimmedSsid)
                            refreshObserved()
                            showForgetPrompt = trimmedSsid
                            ssid = ""
                            password = ""
                            securityType = WifiSecurityType.WPA2
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Save Network")
                }
            }

            item {
                Divider()
            }

            item {
                Text(text = "Saved networks", style = MaterialTheme.typography.subtitle1)
            }

            items(networks) { network ->
                val isRevealed = network.ssid in revealedSsids
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = network.ssid, style = MaterialTheme.typography.body1)
                        Text(text = network.securityType.name, style = MaterialTheme.typography.caption)
                        if (isRevealed) {
                            Text(
                                text = "Password: ${network.password.ifEmpty { "(none)" }}",
                                style = MaterialTheme.typography.caption
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            revealedSsids = if (isRevealed) {
                                revealedSsids - network.ssid
                            } else {
                                revealedSsids + network.ssid
                            }
                        }) {
                            Text(text = if (isRevealed) "Hide" else "Show")
                        }
                        IconButton(onClick = {
                            editingNetwork = network
                            editSsid = network.ssid
                            editPassword = network.password
                            editSecurityType = network.securityType
                        }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = {
                            removeSuggestionsGracefully(wifiManager, listOf(network.toSuggestion()))
                            credentialStore.remove(network.ssid)
                            networks = credentialStore.getAll()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }
        }
    }

    val ssidToForget = showForgetPrompt
    if (ssidToForget != null) {
        AlertDialog(
            onDismissRequest = { showForgetPrompt = null },
            title = { Text(text = "Forget \"$ssidToForget\" in Wi-Fi settings") },
            text = {
                Text(
                    text = "Saved. Now open your phone's Wi-Fi settings and tap \"Forget\" on " +
                        "\"$ssidToForget\" so this app can manage the connection instead."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showForgetPrompt = null
                    onForgetNetworkPrompt()
                }) {
                    Text(text = "Open Wi-Fi Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetPrompt = null }) {
                    Text(text = "Later")
                }
            }
        )
    }

    val original = editingNetwork
    if (original != null) {
        AlertDialog(
            onDismissRequest = { editingNetwork = null },
            title = { Text(text = "Edit network") },
            text = {
                // Compose's Dialog is a separate window from the Activity's, so it doesn't
                // automatically resize/pan for the keyboard the way the main screen does —
                // scrollable + imePadding() is what keeps the password field reachable instead
                // of it being hidden behind the keyboard.
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editSsid,
                        onValueChange = { editSsid = it },
                        label = { Text("Network name (SSID)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editPassword,
                        onValueChange = { editPassword = it },
                        label = { Text("Password") },
                        visualTransformation = if (editSecurityType == WifiSecurityType.OPEN) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        enabled = editSecurityType != WifiSecurityType.OPEN,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(WifiSecurityType.WPA2, WifiSecurityType.WPA3, WifiSecurityType.OPEN).forEach { type ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = editSecurityType == type,
                                    onClick = { editSecurityType = type }
                                )
                                Text(text = type.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmedSsid = editSsid.trim()
                    if (trimmedSsid.isNotBlank()) {
                        val updated = SavedWifiNetwork(trimmedSsid, editPassword, editSecurityType)
                        // Removing and re-adding (rather than just adding) is required even when
                        // only the password/security type changed — addNetworkSuggestions() does
                        // not update an existing suggestion's stored credentials, it just returns
                        // ERROR_ADD_DUPLICATE, silently leaving the OS with the old password.
                        removeSuggestionsGracefully(wifiManager, listOf(original.toSuggestion()))
                        if (trimmedSsid != original.ssid) {
                            credentialStore.remove(original.ssid)
                        }
                        credentialStore.upsert(updated)
                        wifiManager.addNetworkSuggestions(listOf(updated.toSuggestion()))
                        networks = credentialStore.getAll()
                        editingNetwork = null
                    }
                }) {
                    Text(text = "Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingNetwork = null }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}
