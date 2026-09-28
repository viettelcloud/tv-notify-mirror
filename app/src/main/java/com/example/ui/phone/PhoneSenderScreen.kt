package com.example.ui.phone

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.example.data.PreferencesManager
import com.example.network.ConnectionStatus
import com.example.network.DiscoveredTv
import com.example.network.NetworkDiscovery
import com.example.network.PhoneWebSocketClient
import com.example.service.MirroredNotificationLog
import com.example.service.NotificationMirrorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppItem(
    val name: String,
    val packageName: String,
    val isSystemApp: Boolean = false
)

@Composable
fun PhoneSenderScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesManager.getInstance(context) }

    // Observe settings
    val isServiceEnabled by prefs.isServiceEnabled.collectAsState()
    val isPrivacyMode by prefs.isPrivacyMode.collectAsState()
    val isHideOtp by prefs.isHideOtp.collectAsState()
    val savedTvIp by prefs.tvIp.collectAsState()
    val savedTvPort by prefs.tvPort.collectAsState()
    val savedTvName by prefs.tvName.collectAsState()
    val allowedPackages by prefs.allowedPackages.collectAsState()

    // Observe notification service status & client
    val mirrorLogs by NotificationMirrorService.mirrorLogs.collectAsState()
    val webSocketClient = remember { NotificationMirrorService.initClient(scope) }
    val connectionStatus by webSocketClient.status.collectAsState()
    val lastError by webSocketClient.lastErrorMessage.collectAsState()

    // Permission state check
    var hasListenerPermission by remember { mutableStateOf(false) }

    fun checkPermission() {
        hasListenerPermission = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    DisposableEffect(Unit) {
        checkPermission()
        onDispose { }
    }

    // Connect on startup if IP is saved
    LaunchedEffect(savedTvIp, isServiceEnabled) {
        if (savedTvIp.isNotBlank() && isServiceEnabled && connectionStatus == ConnectionStatus.DISCONNECTED) {
            webSocketClient.connect(savedTvIp, savedTvPort)
        }
    }

    // TV Discovery state
    var isScanning by remember { mutableStateOf(false) }
    var discoveredTvs by remember { mutableStateOf<List<DiscoveredTv>>(emptyList()) }
    var manualIpInput by remember(savedTvIp) { mutableStateOf(savedTvIp) }

    fun startTvScan() {
        scope.launch {
            isScanning = true
            val tvs = NetworkDiscovery.discoverTvs(context, timeoutMs = 2500)
            discoveredTvs = tvs
            isScanning = false
            if (tvs.isNotEmpty()) {
                val first = tvs.first()
                prefs.setTvConnection(first.ip, first.port, first.name)
                webSocketClient.connect(first.ip, first.port)
                snackbarHostState.showSnackbar("Discovered & connected to ${first.name}")
            } else {
                snackbarHostState.showSnackbar("No TV found. Enter TV IP manually.")
            }
        }
    }

    // Installed apps loader
    var installedApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var appSearchQuery by remember { mutableStateOf("") }
    var isLoadingApps by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            isLoadingApps = true
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || PreferencesManager.DEFAULT_ALLOWED_PRESETS.contains(it.packageName) }
                .map { appInfo ->
                    AppItem(
                        name = pm.getApplicationLabel(appInfo).toString(),
                        packageName = appInfo.packageName,
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedBy { it.name.lowercase() }
            withContext(Dispatchers.Main) {
                installedApps = apps
                isLoadingApps = false
            }
        }
    }

    // Tabs for Phone screen: Overview / App Selection / Activity Log
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Controls & Wi-Fi", "App Filter (${allowedPackages.size})", "Mirror Log")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        // Top App Bar / Title Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF2563EB), Color(0xFF4F46E5))),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = "CastNotify",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "CastNotify Sender",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Phone to Android TV Mirroring",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }

            // Connection Status Pill
            ConnectionStatusBadge(status = connectionStatus, tvIp = savedTvIp)
        }

        // Tab Navigation
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF1E293B),
            contentColor = Color(0xFF38BDF8)
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }

        // Tab Content
        when (selectedTab) {
            0 -> ControlsTab(
                hasPermission = hasListenerPermission,
                isServiceEnabled = isServiceEnabled,
                isPrivacyMode = isPrivacyMode,
                isHideOtp = isHideOtp,
                savedTvIp = savedTvIp,
                savedTvName = savedTvName,
                manualIpInput = manualIpInput,
                isScanning = isScanning,
                connectionStatus = connectionStatus,
                lastError = lastError,
                onRefreshPermission = { checkPermission() },
                onToggleService = { prefs.setServiceEnabled(it) },
                onTogglePrivacy = { prefs.setPrivacyMode(it) },
                onToggleOtp = { prefs.setHideOtp(it) },
                onIpChanged = { manualIpInput = it },
                onSaveConnectIp = {
                    prefs.setTvConnection(manualIpInput.trim(), 8080)
                    webSocketClient.connect(manualIpInput.trim(), 8080)
                    scope.launch { snackbarHostState.showSnackbar("Connecting to ${manualIpInput.trim()}...") }
                },
                onScanWifi = { startTvScan() },
                onSendTestNotification = {
                    val success = NotificationMirrorService.sendTestPayload(
                        context,
                        customApp = "Zalo",
                        customTitle = "John Doe",
                        customMessage = "Hello, see you on TV!"
                    )
                    scope.launch {
                        if (success) {
                            snackbarHostState.showSnackbar("Test payload mirrored to TV!")
                        } else {
                            snackbarHostState.showSnackbar("Sent test payload (queued, waiting for TV)")
                        }
                    }
                }
            )

            1 -> AppFilterTab(
                installedApps = installedApps,
                allowedPackages = allowedPackages,
                searchQuery = appSearchQuery,
                isLoading = isLoadingApps,
                onSearchChanged = { appSearchQuery = it },
                onToggleApp = { pkg, allowed -> prefs.toggleAppAllowed(pkg, allowed) },
                onSelectAll = {
                    val all = installedApps.map { it.packageName }.toSet()
                    prefs.setAllAppsAllowed(all)
                },
                onSelectNone = {
                    prefs.setAllAppsAllowed(emptySet())
                },
                onSelectPresetsOnly = {
                    prefs.setAllAppsAllowed(PreferencesManager.DEFAULT_ALLOWED_PRESETS)
                }
            )

            2 -> MirrorLogTab(
                logs = mirrorLogs,
                onClearLogs = { NotificationMirrorService.clearLogs() }
            )
        }
    }
}

@Composable
private fun ControlsTab(
    hasPermission: Boolean,
    isServiceEnabled: Boolean,
    isPrivacyMode: Boolean,
    isHideOtp: Boolean,
    savedTvIp: String,
    savedTvName: String,
    manualIpInput: String,
    isScanning: Boolean,
    connectionStatus: ConnectionStatus,
    lastError: String?,
    onRefreshPermission: () -> Unit,
    onToggleService: (Boolean) -> Unit,
    onTogglePrivacy: (Boolean) -> Unit,
    onToggleOtp: (Boolean) -> Unit,
    onIpChanged: (String) -> Unit,
    onSaveConnectIp: () -> Unit,
    onScanWifi: () -> Unit,
    onSendTestNotification: () -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Notification Listener Permission Banner
        item {
            if (!hasPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("permission_alert_card"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFD97706)))),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Permission Required",
                                color = Color(0xFFFEF3C7),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "To mirror incoming notifications (Zalo, Messenger, WhatsApp, SMS) to your TV, grant 'Notification Access' permission.",
                            color = Color(0xFFFDE68A),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                modifier = Modifier.testTag("grant_permission_button")
                            ) {
                                Text("Grant Access in Settings", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onRefreshPermission,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFDE68A))
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Check")
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Enabled",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Notification Listener Active & Listening",
                            color = Color(0xFFD1FAE5),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Master Service Switch Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(
                                    if (isServiceEnabled) Color(0xFF0284C7) else Color(0xFF475569),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Toggle Service",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Mirroring Service",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isServiceEnabled) "Active • Mirroring notifications" else "Paused • Not mirroring",
                                color = if (isServiceEnabled) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Switch(
                        checked = isServiceEnabled,
                        onCheckedChange = onToggleService,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF0284C7)
                        ),
                        modifier = Modifier.testTag("toggle_service_switch")
                    )
                }
            }
        }

        // TV Local Network Connection Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = "TV Connection",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Google TV / Android TV Target",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        Button(
                            onClick = onScanWifi,
                            enabled = !isScanning,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            modifier = Modifier.testTag("scan_wifi_button")
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scanning...", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Wifi, contentDescription = "Scan", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Auto Scan", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "TV WebSocket Server IP (Port 8080):",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualIpInput,
                            onValueChange = onIpChanged,
                            placeholder = { Text("e.g. 192.168.1.100 or 10.0.2.2") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tv_ip_input"),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF38BDF8),
                                unfocusedBorderColor = Color(0xFF475569),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedPlaceholderColor = Color(0xFF64748B),
                                unfocusedPlaceholderColor = Color(0xFF64748B)
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = onSaveConnectIp,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.testTag("connect_tv_button")
                        ) {
                            Text("Connect")
                        }
                    }

                    if (lastError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Error: $lastError",
                            color = Color(0xFFF87171),
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Test Notification Button
                    Button(
                        onClick = onSendTestNotification,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("send_test_notification_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Test Notification")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send Test Notification to TV", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Privacy Controls Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Privacy Shield",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Privacy & Content Shield",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Master Privacy Obscurity
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Full Privacy Mode",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Replaces message body with 'New message received' to hide content from viewers",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = isPrivacyMode,
                            onCheckedChange = onTogglePrivacy,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF10B981)
                            ),
                            modifier = Modifier.testTag("toggle_privacy_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Hide OTP Codes
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mask OTP & Verification Codes",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Automatically detects and replaces 4-8 digit pins with ••••••",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = isHideOtp,
                            onCheckedChange = onToggleOtp,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF10B981)
                            ),
                            modifier = Modifier.testTag("toggle_otp_switch")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppFilterTab(
    installedApps: List<AppItem>,
    allowedPackages: Set<String>,
    searchQuery: String,
    isLoading: Boolean,
    onSearchChanged: (String) -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onSelectPresetsOnly: () -> Unit
) {
    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            installedApps.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search & Shortcuts
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChanged,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("app_search_input"),
            placeholder = { Text("Search installed apps (e.g. Zalo, Messenger, Bank)...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF94A3B8)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChanged("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color(0xFF94A3B8))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF38BDF8),
                unfocusedBorderColor = Color(0xFF475569),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Quick action filters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSelectPresetsOnly,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                modifier = Modifier.weight(1f)
            ) {
                Text("Chat Only", fontSize = 11.sp)
            }
            Button(
                onClick = onSelectAll,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                modifier = Modifier.weight(1f)
            ) {
                Text("Allow All", fontSize = 11.sp)
            }
            Button(
                onClick = onSelectNone,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                modifier = Modifier.weight(1f)
            ) {
                Text("Disable All", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Choose which apps are allowed to mirror notifications on TV (e.g. enable Zalo/Messenger, disable Banking):",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF38BDF8))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isChecked = allowedPackages.contains(app.packageName)
                    val isBankingOrSensitive = app.name.contains("Bank", ignoreCase = true) ||
                            app.packageName.contains("banking", ignoreCase = true) ||
                            app.packageName.contains("crypto", ignoreCase = true) ||
                            app.packageName.contains("wallet", ignoreCase = true)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleApp(app.packageName, !isChecked) }
                            .testTag("app_item_${app.packageName}"),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (isChecked) Color(0xFF0284C7) else Color(0xFF334155),
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = app.name.take(1).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = app.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        if (isBankingOrSensitive) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = "Sensitive App",
                                                tint = Color(0xFFFBBF24),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = app.packageName,
                                        color = Color(0xFF64748B),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { onToggleApp(app.packageName, it) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF0284C7),
                                    checkmarkColor = Color.White,
                                    uncheckedColor = Color(0xFF64748B)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorLogTab(
    logs: List<MirroredNotificationLog>,
    onClearLogs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Live Activity Log (${logs.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            if (logs.isNotEmpty()) {
                IconButton(onClick = onClearLogs) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear logs",
                        tint = Color(0xFF94A3B8)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "No logs",
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No notifications mirrored yet",
                        color = Color(0xFF94A3B8),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Incoming notifications matching your filters will be logged here.",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    val formattedTime = timeFormat.format(Date(log.timestamp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = log.appName,
                                        color = Color(0xFF38BDF8),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "• $formattedTime",
                                        color = Color(0xFF64748B),
                                        fontSize = 11.sp
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when {
                                        log.status.contains("Mirrored") || log.status.contains("Sent") -> Color(0xFF065F46)
                                        log.status.contains("Blocked") -> Color(0xFF7F1D1D)
                                        else -> Color(0xFF334155)
                                    }
                                ) {
                                    Text(
                                        text = log.status,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            if (log.title.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = log.title,
                                    color = Color(0xFFF1F5F9),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }

                            if (log.message.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = log.message,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusBadge(status: ConnectionStatus, tvIp: String) {
    val (bgColor, textColor, text) = when (status) {
        ConnectionStatus.CONNECTED -> Triple(Color(0xFF065F46), Color(0xFF34D399), "Connected to TV")
        ConnectionStatus.CONNECTING -> Triple(Color(0xFF854D0E), Color(0xFFFDE047), "Connecting...")
        ConnectionStatus.DISCONNECTED -> Triple(Color(0xFF334155), Color(0xFF94A3B8), "Disconnected")
        ConnectionStatus.ERROR -> Triple(Color(0xFF7F1D1D), Color(0xFFFCA5A5), "Error")
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        modifier = Modifier.testTag("connection_status_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(textColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
