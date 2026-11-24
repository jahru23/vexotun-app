package com.vexotun.app.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vexotun.app.model.DEFAULT_XRAY_TEMPLATE
import com.vexotun.app.model.SplitTunnelMode
import com.vexotun.app.model.SshTransport
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.vpn.ConnectionStatus
import com.vexotun.app.vpn.VpnRuntimeState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onRequestVpnStart: () -> Unit,
    viewModel: MainScreenViewModel = viewModel()
) {
    val config by viewModel.config.collectAsState()
    val runtime by viewModel.runtimeState.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vexotun", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        StatusBadge(runtime.status)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Tunnel") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Config") },
                    label = { Text("Config") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Logs") },
                    label = { Text("Logs") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Apps, contentDescription = "Split Tunnel") },
                    label = { Text("Apps") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> HomeTab(
                    config = config,
                    runtime = runtime,
                    isPinging = isPinging,
                    onModeChange = viewModel::setMode,
                    onStartClick = onRequestVpnStart,
                    onStopClick = viewModel::stopVpn,
                    onPingClick = viewModel::runPingCheck
                )
                1 -> ConfigTab(
                    config = config,
                    onUpdate = viewModel::updateConfig,
                    onTransportChange = viewModel::setSshTransport
                )
                2 -> LogsTab(
                    runtime = runtime,
                    onClear = viewModel::clearLogs
                )
                3 -> SplitTunnelTab(
                    config = config,
                    apps = apps,
                    onSplitModeChange = viewModel::setSplitMode,
                    onTogglePackage = viewModel::toggleSplitPackage
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ConnectionStatus) {
    val (bg, label) = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF1B5E20) to "CONNECTED"
        ConnectionStatus.CONNECTING -> Color(0xFFE65100) to "CONNECTING"
        ConnectionStatus.ERROR -> Color(0xFFB71C1C) to "ERROR"
        ConnectionStatus.DISCONNECTED -> Color(0xFF455A64) to "IDLE"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HomeTab(
    config: VexotunConfig,
    runtime: VpnRuntimeState,
    isPinging: Boolean,
    onModeChange: (TunnelMode) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPingClick: () -> Unit
) {
    val isActive = runtime.status == ConnectionStatus.CONNECTED || runtime.status == ConnectionStatus.CONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode switcher
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TunnelMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = config.mode == mode,
                    onClick = { if (!isActive) onModeChange(mode) },
                    enabled = !isActive,
                    shape = SegmentedButtonDefaults.itemShape(index, TunnelMode.entries.size)
                ) {
                    Text(if (mode == TunnelMode.SSH) "SSH + Injector" else "Xray Core")
                }
            }
        }

        // Main Connect Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { if (isActive) onStopClick() else onStartClick() },
                    modifier = Modifier.size(120.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(if (isActive) "STOP" else "START", fontWeight = FontWeight.Bold)
                    }
                }

                Text(
                    text = runtime.statusMessage,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                val summary = if (config.mode == TunnelMode.SSH) {
                    val host = config.ssh.host.ifBlank { "(host belum diisi)" }
                    "${config.ssh.transport.label} • $host:${config.ssh.port}"
                } else {
                    "Xray JSON • SOCKS 127.0.0.1:${config.socksPort}"
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Traffic & Ping Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                title = "Upload",
                value = formatBytes(runtime.bytesUp),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Download",
                value = formatBytes(runtime.bytesDown),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Ping",
                value = runtime.pingMs?.let { "${it} ms" } ?: "--",
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedButton(
            onClick = onPingClick,
            enabled = !isPinging,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.NetworkPing, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isPinging) "Checking Latency..." else "Test Ping / Latency")
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ConfigTab(
    config: VexotunConfig,
    onUpdate: ((VexotunConfig) -> VexotunConfig) -> Unit,
    onTransportChange: (SshTransport) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (config.mode == TunnelMode.SSH) {
            Text("SSH Transport", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SshTransport.entries.forEach { t ->
                    FilterChip(
                        selected = config.ssh.transport == t,
                        onClick = { onTransportChange(t) },
                        label = { Text(t.label) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.ssh.host,
                    onValueChange = { v -> onUpdate { it.copy(ssh = it.ssh.copy(host = v)) } },
                    label = { Text("SSH Host / IP") },
                    singleLine = true,
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = config.ssh.port.toString(),
                    onValueChange = { v ->
                        val p = v.filter(Char::isDigit).toIntOrNull() ?: 22
                        onUpdate { it.copy(ssh = it.ssh.copy(port = p)) }
                    },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.ssh.username,
                    onValueChange = { v -> onUpdate { it.copy(ssh = it.ssh.copy(username = v)) } },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = config.ssh.password,
                    onValueChange = { v -> onUpdate { it.copy(ssh = it.ssh.copy(password = v)) } },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (config.ssh.transport != SshTransport.DIRECT) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = config.ssh.proxyHost,
                        onValueChange = { v -> onUpdate { it.copy(ssh = it.ssh.copy(proxyHost = v)) } },
                        label = { Text("Remote Proxy (opsional)") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = config.ssh.proxyPort.toString(),
                        onValueChange = { v ->
                            val p = v.filter(Char::isDigit).toIntOrNull() ?: 8080
                            onUpdate { it.copy(ssh = it.ssh.copy(proxyPort = p)) }
                        },
                        label = { Text("Proxy Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (config.ssh.transport == SshTransport.SSL || config.ssh.transport == SshTransport.WSS) {
                OutlinedTextField(
                    value = config.ssh.sni,
                    onValueChange = { v -> onUpdate { it.copy(ssh = it.ssh.copy(sni = v)) } },
                    label = { Text("SNI (Server Name Indication)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (config.ssh.transport == SshTransport.WS || config.ssh.transport == SshTransport.WSS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = config.ssh.wsPath,
                        onValueChange = { v -> onUpdate { it.copy(ssh = it.ssh.copy(wsPath = v)) } },
                        label = { Text("WS Path") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = config.ssh.wsHost,
                        onValueChange = { v -> onUpdate { it.copy(ssh = it.ssh.copy(wsHost = v)) } },
                        label = { Text("WS Host Header") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (config.ssh.transport == SshTransport.HTTP_PROXY || config.ssh.transport == SshTransport.SSL) {
                Text("HTTP Payload Injector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val tags = listOf("[crlf]", "[host]", "[host_port]", "[split]", "[delay_split]", "[ua]", "[protocol]")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tags.forEach { tag ->
                        SuggestionChip(
                            onClick = {
                                onUpdate { it.copy(ssh = it.ssh.copy(payload = it.ssh.payload + tag)) }
                            },
                            label = { Text(tag, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = config.ssh.payload,
                    onValueChange = { v -> onUpdate { it.copy(ssh = it.ssh.copy(payload = v)) } },
                    label = { Text("Payload Template") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Xray Config JSON", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = { onUpdate { it.copy(xrayConfigJson = DEFAULT_XRAY_TEMPLATE) } }
                ) {
                    Text("Reset Template")
                }
            }
            OutlinedTextField(
                value = config.xrayConfigJson,
                onValueChange = { v -> onUpdate { it.copy(xrayConfigJson = v) } },
                minLines = 12,
                maxLines = 22,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(4.dp))
        Text("Network & TUN Settings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = config.dnsServer,
                onValueChange = { v -> onUpdate { it.copy(dnsServer = v) } },
                label = { Text("Custom DNS") },
                singleLine = true,
                modifier = Modifier.weight(1.5f)
            )
            OutlinedTextField(
                value = config.mtu.toString(),
                onValueChange = { v ->
                    val m = v.filter(Char::isDigit).toIntOrNull() ?: 1500
                    onUpdate { it.copy(mtu = m) }
                },
                label = { Text("MTU") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LogsTab(
    runtime: VpnRuntimeState,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(runtime.logs.size) {
        if (runtime.logs.isNotEmpty()) {
            listState.animateScrollToItem(runtime.logs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tunnel Logs (${runtime.logs.size})", fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = {
                    val text = runtime.logs.joinToString("\n") { "${it.level}: ${it.message}" }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("vexotun_logs", text))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs")
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Logs")
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101418))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(runtime.logs) { entry ->
                val color = when (entry.level) {
                    "ERROR" -> Color(0xFFFF6B6B)
                    "WARN" -> Color(0xFFFFD93D)
                    else -> Color(0xFFA8E6CF)
                }
                Text(
                    text = entry.message,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SplitTunnelTab(
    config: VexotunConfig,
    apps: List<InstalledApp>,
    onSplitModeChange: (SplitTunnelMode) -> Unit,
    onTogglePackage: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Split Tunneling Mode", fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = config.splitMode == SplitTunnelMode.ALL_APPS,
                onClick = { onSplitModeChange(SplitTunnelMode.ALL_APPS) },
                label = { Text("Semua Aplikasi") }
            )
            FilterChip(
                selected = config.splitMode == SplitTunnelMode.INCLUDE_ONLY,
                onClick = { onSplitModeChange(SplitTunnelMode.INCLUDE_ONLY) },
                label = { Text("Hanya yang Dipilih") }
            )
            FilterChip(
                selected = config.splitMode == SplitTunnelMode.EXCLUDE_SELECTED,
                onClick = { onSplitModeChange(SplitTunnelMode.EXCLUDE_SELECTED) },
                label = { Text("Kecualikan yang Dipilih") }
            )
        }

        if (config.splitMode != SplitTunnelMode.ALL_APPS) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Cari aplikasi...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in config.splitPackages
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTogglePackage(app.packageName) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { onTogglePackage(app.packageName) })
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.Medium)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                "Semua trafik aplikasi di perangkat akan melewati tunnel VPN (kecuali Vexotun sendiri).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
