package com.vexotun.app.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vexotun.app.model.SplitTunnelMode
import com.vexotun.app.model.SshTransport
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.TunnelProfile
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.model.XrayProtocol
import com.vexotun.app.model.XrayTransport
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
    var showImportDialog by remember { mutableStateOf(false) }

    if (showImportDialog) {
        ImportLinkDialog(
            onDismiss = { showImportDialog = false },
            onImport = { linkText ->
                val result = viewModel.importFromLink(linkText)
                if (result.isSuccess) {
                    showImportDialog = false
                }
                result
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vexotun", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        StatusBadge(runtime.status)
                    }
                },
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.Link, contentDescription = "Import Link")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Tunnel") },
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
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Log") },
                    label = { Text("Log") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Apps, contentDescription = "Aplikasi") },
                    label = { Text("Aplikasi") }
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
                    onSelectProfile = viewModel::selectProfile,
                    onOpenImport = { showImportDialog = true },
                    onStartClick = onRequestVpnStart,
                    onStopClick = viewModel::stopVpn,
                    onPingClick = viewModel::runPingCheck
                )
                1 -> ConfigTab(
                    config = config,
                    onSelectProfile = viewModel::selectProfile,
                    onAddProfile = viewModel::addBlankProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onOpenImport = { showImportDialog = true },
                    onUpdateProfile = viewModel::updateActiveProfile,
                    onUpdateConfig = viewModel::updateConfig
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
private fun ImportLinkDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Result<TunnelProfile>
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Link Konfigurasi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tempel 1 link vmess://, vless://, trojan://, atau ss://",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        errorMsg = null
                    },
                    placeholder = { Text("vmess://...") },
                    minLines = 3,
                    maxLines = 5,
                    isError = errorMsg != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg != null) {
                    Text(
                        errorMsg.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (clip.isNotBlank()) {
                            input = clip
                            errorMsg = null
                        }
                    }
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tempel dari Clipboard")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val res = onImport(input)
                res.onFailure { errorMsg = it.message ?: "Link tidak valid" }
            }) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
private fun StatusBadge(status: ConnectionStatus) {
    val (bg, label) = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF1B5E20) to "TERHUBUNG"
        ConnectionStatus.CONNECTING -> Color(0xFFE65100) to "MENGHUBUNGKAN"
        ConnectionStatus.ERROR -> Color(0xFFB71C1C) to "GAGAL"
        ConnectionStatus.DISCONNECTED -> Color(0xFF455A64) to "SIAP"
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
    onSelectProfile: (String) -> Unit,
    onOpenImport: () -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPingClick: () -> Unit
) {
    val active = config.activeProfile()
    val isActive = runtime.status == ConnectionStatus.CONNECTED || runtime.status == ConnectionStatus.CONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Profile quick selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            config.profiles.forEach { profile ->
                val selected = profile.id == active.id
                FilterChip(
                    selected = selected,
                    onClick = { if (!isActive) onSelectProfile(profile.id) },
                    label = { Text("${profile.name} (${profile.mode.label})") }
                )
            }
            OutlinedButton(
                onClick = onOpenImport,
                enabled = !isActive
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import")
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
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { if (isActive) onStopClick() else onStartClick() },
                    modifier = Modifier.size(124.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(if (isActive) "PUTUSKAN" else "HUBUNGKAN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Text(
                    text = active.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = active.summary(),
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
            StatCard("Upload", formatBytes(runtime.bytesUp), Modifier.weight(1f))
            StatCard("Download", formatBytes(runtime.bytesDown), Modifier.weight(1f))
            StatCard("Ping", runtime.pingMs?.let { "$it ms" } ?: "--", Modifier.weight(1f))
        }

        OutlinedButton(
            onClick = onPingClick,
            enabled = !isPinging,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.NetworkPing, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isPinging) "Mengukur Latency..." else "Cek Ping")
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
    onSelectProfile: (String) -> Unit,
    onAddProfile: (TunnelMode) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onOpenImport: () -> Unit,
    onUpdateProfile: ((TunnelProfile) -> TunnelProfile) -> Unit,
    onUpdateConfig: ((VexotunConfig) -> VexotunConfig) -> Unit
) {
    val active = config.activeProfile()
    var showNetworkSettings by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Profile Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpenImport,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import Link")
            }
            OutlinedButton(
                onClick = { onAddProfile(TunnelMode.XRAY) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Xray")
            }
            OutlinedButton(
                onClick = { onAddProfile(TunnelMode.SSH) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("SSH")
            }
        }

        // Saved Profiles Horizontal Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            config.profiles.forEach { profile ->
                val isSelected = profile.id == active.id
                Card(
                    onClick = { onSelectProfile(profile.id) },
                    border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                profile.name,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                profile.mode.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (config.profiles.size > 1) {
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = { onDeleteProfile(profile.id) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Hapus Profil",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Profile Name & Mode
        OutlinedTextField(
            value = active.name,
            onValueChange = { v -> onUpdateProfile { it.copy(name = v) } },
            label = { Text("Nama Profil") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TunnelMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = active.mode == mode,
                    onClick = { onUpdateProfile { it.copy(mode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index, TunnelMode.entries.size)
                ) {
                    Text(mode.label)
                }
            }
        }

        if (active.mode == TunnelMode.XRAY) {
            XrayProfileEditor(profile = active, onUpdateProfile = onUpdateProfile)
        } else {
            SshProfileEditor(profile = active, onUpdateProfile = onUpdateProfile)
        }

        // Collapsible Network Settings
        Card(
            onClick = { showNetworkSettings = !showNetworkSettings },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pengaturan Jaringan (DNS & MTU)", fontWeight = FontWeight.Medium)
                Icon(
                    if (showNetworkSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
        }

        AnimatedVisibility(visible = showNetworkSettings) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.dnsServer,
                    onValueChange = { v -> onUpdateConfig { it.copy(dnsServer = v) } },
                    label = { Text("DNS") },
                    singleLine = true,
                    modifier = Modifier.weight(1.5f)
                )
                OutlinedTextField(
                    value = config.mtu.toString(),
                    onValueChange = { v ->
                        val m = v.filter(Char::isDigit).toIntOrNull() ?: 1500
                        onUpdateConfig { it.copy(mtu = m) }
                    },
                    label = { Text("MTU") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun XrayProfileEditor(
    profile: TunnelProfile,
    onUpdateProfile: ((TunnelProfile) -> TunnelProfile) -> Unit
) {
    val x = profile.xray

    Text("Protokol", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        XrayProtocol.entries.forEach { proto ->
            FilterChip(
                selected = x.protocol == proto,
                onClick = { onUpdateProfile { it.copy(xray = it.xray.copy(protocol = proto)) } },
                label = { Text(proto.label) }
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = x.address,
            onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(address = v)) } },
            label = { Text("Address / Host") },
            singleLine = true,
            modifier = Modifier.weight(2f)
        )
        OutlinedTextField(
            value = x.port.toString(),
            onValueChange = { v ->
                val p = v.filter(Char::isDigit).toIntOrNull() ?: 443
                onUpdateProfile { it.copy(xray = it.xray.copy(port = p)) }
            },
            label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }

    OutlinedTextField(
        value = x.id,
        onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(id = v)) } },
        label = {
            Text(
                when (x.protocol) {
                    XrayProtocol.VMESS, XrayProtocol.VLESS -> "UUID"
                    else -> "Password"
                }
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    if (x.protocol == XrayProtocol.SHADOWSOCKS) {
        OutlinedTextField(
            value = x.security,
            onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(security = v)) } },
            label = { Text("Method / Cipher") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Text("Transport", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        XrayTransport.entries.forEach { net ->
            FilterChip(
                selected = x.network == net,
                onClick = { onUpdateProfile { it.copy(xray = it.xray.copy(network = net)) } },
                label = { Text(net.label) }
            )
        }
    }

    if (x.network != XrayTransport.TCP) {
        OutlinedTextField(
            value = x.path,
            onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(path = v)) } },
            label = {
                Text(if (x.network == XrayTransport.GRPC) "Service Name" else "Path")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = x.host,
            onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(host = v)) } },
            label = { Text("Host Header") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = x.sni,
            onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(sni = v)) } },
            label = { Text("SNI") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Gunakan TLS")
        Switch(
            checked = x.tls,
            onCheckedChange = { checked ->
                onUpdateProfile { it.copy(xray = it.xray.copy(tls = checked)) }
            }
        )
    }

    if (x.tls) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Allow Insecure (lewati verifikasi sertifikat)")
            Switch(
                checked = x.allowInsecure,
                onCheckedChange = { checked ->
                    onUpdateProfile { it.copy(xray = it.xray.copy(allowInsecure = checked)) }
                }
            )
        }
    }
}

@Composable
private fun SshProfileEditor(
    profile: TunnelProfile,
    onUpdateProfile: ((TunnelProfile) -> TunnelProfile) -> Unit
) {
    val s = profile.ssh
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    Text("Transport", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SshTransport.entries.forEach { t ->
            FilterChip(
                selected = s.transport == t,
                onClick = { onUpdateProfile { it.copy(ssh = it.ssh.copy(transport = t)) } },
                label = { Text(t.label) }
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = s.host,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(host = v)) } },
            label = { Text("Host / IP") },
            singleLine = true,
            modifier = Modifier.weight(2f)
        )
        OutlinedTextField(
            value = s.port.toString(),
            onValueChange = { v ->
                val p = v.filter(Char::isDigit).toIntOrNull() ?: 22
                onUpdateProfile { it.copy(ssh = it.ssh.copy(port = p)) }
            },
            label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = s.username,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(username = v)) } },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = s.password,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(password = v)) } },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }

    if (s.transport == SshTransport.SSL || s.transport == SshTransport.WSS) {
        OutlinedTextField(
            value = s.sni,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(sni = v)) } },
            label = { Text("SNI") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (s.transport == SshTransport.WS || s.transport == SshTransport.WSS) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = s.wsPath,
                onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(wsPath = v)) } },
                label = { Text("WS Path") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = s.wsHost,
                onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(wsHost = v)) } },
                label = { Text("WS Host") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (s.transport != SshTransport.DIRECT) {
        Card(
            onClick = { showAdvanced = !showAdvanced },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Proxy & Custom Payload", fontWeight = FontWeight.Medium)
                Icon(
                    if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
        }

        AnimatedVisibility(visible = showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = s.proxyHost,
                        onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(proxyHost = v)) } },
                        label = { Text("Proxy Host (opsional)") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = s.proxyPort.toString(),
                        onValueChange = { v ->
                            val p = v.filter(Char::isDigit).toIntOrNull() ?: 8080
                            onUpdateProfile { it.copy(ssh = it.ssh.copy(proxyPort = p)) }
                        },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (s.transport == SshTransport.HTTP_PROXY || s.transport == SshTransport.SSL) {
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
                                    onUpdateProfile { it.copy(ssh = it.ssh.copy(payload = it.ssh.payload + tag)) }
                                },
                                label = { Text(tag, fontSize = 11.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = s.payload,
                        onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(payload = v)) } },
                        label = { Text("Payload") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
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
            Text("Log Koneksi (${runtime.logs.size})", fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = {
                    val text = runtime.logs.joinToString("\n") { "${it.level}: ${it.message}" }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("vexotun_logs", text))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Salin Log")
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Bersihkan Log")
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
        Text("Filter Aplikasi", fontWeight = FontWeight.Bold)
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
                label = { Text("Hanya Dipilih") }
            )
            FilterChip(
                selected = config.splitMode == SplitTunnelMode.EXCLUDE_SELECTED,
                onClick = { onSplitModeChange(SplitTunnelMode.EXCLUDE_SELECTED) },
                label = { Text("Kecualikan") }
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
                "Semua trafik aplikasi di perangkat akan melewati tunnel VPN.",
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
