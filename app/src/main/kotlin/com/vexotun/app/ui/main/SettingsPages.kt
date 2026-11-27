package com.vexotun.app.ui.main

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vexotun.app.model.CoreSshPayload
import com.vexotun.app.model.LinkExporter
import com.vexotun.app.model.SlowDnsRecordType
import com.vexotun.app.model.SplitTunnelMode
import com.vexotun.app.model.SshProfile
import com.vexotun.app.model.SshTransport
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.TunnelProfile
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.model.VxtLockInfo
import com.vexotun.app.model.XrayProtocol
import com.vexotun.app.model.XrayTransport
import com.vexotun.app.vpn.ConnectionStatus
import com.vexotun.app.vpn.TrafficStatsState
import com.vexotun.app.vpn.TunnelState
import com.vexotun.app.vpn.VexotunVpnService
import com.vexotun.app.vpn.VpnRuntimeState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
internal fun SplitTunnelPage(
    config: VexotunConfig,
    apps: List<InstalledApp>,
    isVpnConnected: Boolean,
    onSplitModeChange: (SplitTunnelMode) -> Unit,
    onTogglePackage: (String) -> Unit,
    onReconnectVpn: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var needsReconnect by remember { mutableStateOf(false) }
    val selectedSet = remember(config.splitPackages) { config.splitPackages.toHashSet() }
    val filtered = remember(apps, query, showSystemApps, selectedSet) {
        apps.filter { app ->
            val visibleByType = showSystemApps || app.isLauncher || (app.packageName in selectedSet)
            val matchesQuery = query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            visibleByType && matchesQuery
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (needsReconnect && isVpnConnected) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Filter berubah. Sambung ulang agar berlaku.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            needsReconnect = false
                            onReconnectVpn()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Sambung Ulang", fontSize = 12.sp)
                    }
                }
            }
        }

        val splitModes = listOf(
            SplitTunnelMode.ALL_APPS to "Semua",
            SplitTunnelMode.INCLUDE_ONLY to "Dipilih",
            SplitTunnelMode.EXCLUDE_SELECTED to "Kecuali"
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            splitModes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = config.splitMode == mode,
                    onClick = {
                        onSplitModeChange(mode)
                        if (isVpnConnected) needsReconnect = true
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, splitModes.size)
                ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (config.splitMode != SplitTunnelMode.ALL_APPS) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Cari aplikasi...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSystemApps = !showSystemApps }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
                Spacer(Modifier.width(6.dp))
                Text("Tampilkan aplikasi sistem", style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in selectedSet
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onTogglePackage(app.packageName)
                                if (isVpnConnected) needsReconnect = true
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                onTogglePackage(app.packageName)
                                if (isVpnConnected) needsReconnect = true
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                app.label,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    "Semua trafik aplikasi di perangkat akan melewati tunnel VPN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun SettingsPage(
    config: VexotunConfig,
    onUpdateConfig: ((VexotunConfig) -> VexotunConfig) -> Unit
) {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    val batteryExempt = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true
    }

    val dnsPresets = remember {
        listOf(
            "8.8.8.8" to "Google (8.8.8.8)",
            "1.1.1.1" to "Cloudflare (1.1.1.1)",
            "94.140.14.14" to "AdGuard (94.140.14.14)"
        )
    }
    var customDnsSelected by remember(config.dnsServer) {
        mutableStateOf(dnsPresets.none { it.first == config.dnsServer.trim() })
    }
    val selectedDnsLabel = if (customDnsSelected) {
        "Custom"
    } else {
        dnsPresets.firstOrNull { it.first == config.dnsServer.trim() }?.second ?: "Custom"
    }
    val localIp = remember(config.allowLan) {
        if (config.allowLan) findLocalLanIp() else "127.0.0.1"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsSwitchRow(
                    title = "Mode Gelap",
                    subtitle = "Tampilan gelap untuk kenyamanan mata",
                    checked = config.darkTheme,
                    onCheckedChange = { dark -> onUpdateConfig { it.copy(darkTheme = dark) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsSwitchRow(
                    title = "Auto-Reconnect",
                    subtitle = "Sambung ulang otomatis saat pindah WiFi / Seluler",
                    checked = config.autoReconnect,
                    onCheckedChange = { auto -> onUpdateConfig { it.copy(autoReconnect = auto) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsSwitchRow(
                    title = "Bypass Jaringan Lokal",
                    subtitle = "Lewatkan trafik IP lokal langsung tanpa tunnel",
                    checked = config.bypassLan,
                    onCheckedChange = { bypass -> onUpdateConfig { it.copy(bypassLan = bypass) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsSwitchRow(
                    title = "Jaga Koneksi (WakeLock)",
                    subtitle = "Mencegah sistem menidurkan tunnel saat layar mati",
                    checked = config.wakeLock,
                    onCheckedChange = { wl -> onUpdateConfig { it.copy(wakeLock = wl) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !batteryExempt) {
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            } else {
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            }
                            runCatching { context.startActivity(intent) }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Optimasi Baterai", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (batteryExempt) "Sudah dikecualikan (Aman)" else "Ketuk untuk kecualikan dari pembatasan baterai",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (batteryExempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsSwitchRow(
                    title = "Bagikan VPN via Hotspot",
                    subtitle = "Buka Proxy HTTP & SOCKS5 untuk laptop/HP lain",
                    checked = config.allowLan,
                    onCheckedChange = { lan -> onUpdateConfig { it.copy(allowLan = lan) } }
                )
                if (config.allowLan) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "HTTP Proxy: $localIp:${config.httpPort}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "SOCKS5 Proxy: $localIp:${config.socksPort}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(
                            onClick = {
                                val text = "HTTP $localIp:${config.httpPort} | SOCKS5 $localIp:${config.socksPort}"
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("vexotun_proxy", text))
                                Toast.makeText(context, "Alamat proxy disalin", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Salin Proxy", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Jaringan", fontWeight = FontWeight.SemiBold)
                CompactDropdown(
                    label = "Preset DNS",
                    selectedLabel = selectedDnsLabel,
                    options = dnsPresets.map { it.first } + "custom",
                    optionLabel = { key ->
                        dnsPresets.firstOrNull { it.first == key }?.second ?: "Custom"
                    },
                    onSelect = { key ->
                        if (key == "custom") {
                            customDnsSelected = true
                        } else {
                            customDnsSelected = false
                            onUpdateConfig { it.copy(dnsServer = key) }
                        }
                    }
                )
                if (customDnsSelected) {
                    OutlinedTextField(
                        value = config.dnsServer,
                        onValueChange = { v -> onUpdateConfig { it.copy(dnsServer = v.replace("\n", "")) } },
                        label = { Text("DNS Custom") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                NumericTextField(
                    value = config.mtu,
                    defaultValue = 1420,
                    onValueChange = { m -> onUpdateConfig { it.copy(mtu = m) } },
                    label = "MTU",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

internal fun findLocalLanIp(): String {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: "192.168.43.1"
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

