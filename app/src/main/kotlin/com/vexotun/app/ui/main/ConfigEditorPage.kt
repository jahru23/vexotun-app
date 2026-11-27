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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigEditorPage(
    profile: TunnelProfile,
    isLockedByVpn: Boolean = false,
    onUpdateProfile: ((TunnelProfile) -> TunnelProfile) -> Unit,
    onDone: () -> Unit
) {
    val canEdit = !isLockedByVpn && !profile.isLocked
    val guardedUpdate: ((TunnelProfile) -> TunnelProfile) -> Unit = { transform ->
        if (canEdit) onUpdateProfile(transform)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isLockedByVpn) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "Profil ini sedang digunakan oleh VPN. Putus koneksi terlebih dahulu untuk mengubahnya.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        OutlinedTextField(
            value = profile.name,
            onValueChange = { v -> guardedUpdate { it.copy(name = v) } },
            label = { Text("Nama Config") },
            enabled = canEdit,
            readOnly = !canEdit,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (profile.mode == TunnelMode.XRAY) {
            XrayProfileEditor(profile = profile, onUpdateProfile = guardedUpdate)
        } else {
            SshProfileEditor(profile = profile, onUpdateProfile = guardedUpdate)
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Selesai", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun NumericTextField(
    value: Int,
    defaultValue: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(if (value > 0) value.toString() else "") }
    LaunchedEffect(value) {
        val currentParsed = text.toIntOrNull() ?: 0
        if (value > 0 && value != currentParsed) {
            text = value.toString()
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(5)
            text = digits
            onValueChange(digits.toIntOrNull() ?: 0)
        },
        placeholder = { Text(defaultValue.toString()) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> CompactDropdown(
    label: String,
    selectedLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
internal fun XrayProfileEditor(
    profile: TunnelProfile,
    onUpdateProfile: ((TunnelProfile) -> TunnelProfile) -> Unit
) {
    val x = profile.xray

    CompactDropdown(
        label = "Protokol",
        selectedLabel = x.protocol.label,
        options = XrayProtocol.entries,
        optionLabel = { it.label },
        onSelect = { proto -> onUpdateProfile { it.copy(xray = it.xray.copy(protocol = proto)) } }
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = x.address,
            onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(address = v)) } },
            label = { Text("Host / Address") },
            singleLine = true,
            modifier = Modifier.weight(2f)
        )
        NumericTextField(
            value = x.port,
            defaultValue = 443,
            onValueChange = { p -> onUpdateProfile { it.copy(xray = it.xray.copy(port = p)) } },
            label = "Port",
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

    CompactDropdown(
        label = "Transport",
        selectedLabel = x.network.label,
        options = XrayTransport.entries,
        optionLabel = { it.label },
        onSelect = { net -> onUpdateProfile { it.copy(xray = it.xray.copy(network = net)) } }
    )

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

    OutlinedTextField(
        value = x.host,
        onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(host = v.replace("\n", ""))) } },
        label = { Text("Host Header") },
        singleLine = false,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = x.sni,
        onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(sni = v.replace("\n", ""))) } },
        label = { Text("SNI") },
        singleLine = false,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth()
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Gunakan TLS", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = x.tls,
                    onCheckedChange = { checked ->
                        onUpdateProfile { it.copy(xray = it.xray.copy(tls = checked)) }
                    }
                )
            }

            if (x.flow.isBlank() && x.network != XrayTransport.GRPC && x.network != XrayTransport.XHTTP) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Xray Mux (Multi-koneksi)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = x.muxEnabled,
                        onCheckedChange = { checked ->
                            onUpdateProfile { it.copy(xray = it.xray.copy(muxEnabled = checked)) }
                        }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Gunakan payload dan proxy kustom",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = x.useCustomPayload,
                    onCheckedChange = { checked ->
                        onUpdateProfile { it.copy(xray = it.xray.copy(useCustomPayload = checked)) }
                    }
                )
            }
        }
    }

    if (x.useCustomPayload) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = x.proxyHost,
                onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(proxyHost = v.replace("\n", ""))) } },
                label = { Text("Proxy Host (Opsional)") },
                singleLine = true,
                modifier = Modifier.weight(2f)
            )
            NumericTextField(
                value = x.proxyPort,
                defaultValue = 8080,
                onValueChange = { p -> onUpdateProfile { it.copy(xray = it.xray.copy(proxyPort = p)) } },
                label = "Port",
                modifier = Modifier.weight(1f)
            )
        }
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
                        onUpdateProfile { it.copy(xray = it.xray.copy(payload = it.xray.payload + tag)) }
                    },
                    label = { Text(tag, fontSize = 11.sp) }
                )
            }
        }
        OutlinedTextField(
            value = x.payload,
            onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(payload = v)) } },
            label = { Text("Payload Xray") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Serializable
private data class SlowDnsScanCandidateUi(
    val resolver: String = "",
    val qtype: String = "",
    val mtu: Int = 0,
    val payloadBytes: Int = 0,
    val queryCap: Int = 0,
    val rttMs: Long = 0
)

@Serializable
private data class SlowDnsScanResponseUi(
    val ok: Boolean = false,
    val candidates: List<SlowDnsScanCandidateUi> = emptyList(),
    val error: String = ""
)

private val scanJson = Json { ignoreUnknownKeys = true }

@Composable
internal fun SshProfileEditor(
    profile: TunnelProfile,
    onUpdateProfile: ((TunnelProfile) -> TunnelProfile) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = profile.ssh
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var isScanningDns by remember { mutableStateOf(false) }
    var scanCandidates by remember { mutableStateOf<List<SlowDnsScanCandidateUi>>(emptyList()) }
    var scanError by remember { mutableStateOf<String?>(null) }

    OutlinedButton(
        onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            val parsed = parseSshAccountPaste(clip)
            if (parsed != null) {
                onUpdateProfile { p ->
                    val isSd = parsed.nameserver.isNotBlank() || parsed.publicKey.isNotBlank()
                    p.copy(
                        ssh = p.ssh.copy(
                            host = parsed.host.ifBlank { p.ssh.host },
                            port = if (parsed.port > 0) parsed.port else p.ssh.port,
                            username = parsed.username.ifBlank { p.ssh.username },
                            password = parsed.password.ifBlank { p.ssh.password },
                            transport = if (isSd) SshTransport.SLOWDNS else p.ssh.transport,
                            dnsServer = if (isSd) "Auto" else p.ssh.dnsServer,
                            dnsType = if (isSd) SlowDnsRecordType.AUTO else p.ssh.dnsType,
                            nameserver = parsed.nameserver.ifBlank { p.ssh.nameserver },
                            publicKey = parsed.publicKey.ifBlank { p.ssh.publicKey }
                        )
                    )
                }
                Toast.makeText(context, "Akun SSH ditempel", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Format akun SSH di clipboard tidak dikenali", Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp)
    ) {
        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Tempel Akun SSH")
    }

    CompactDropdown(
        label = "Transport",
        selectedLabel = s.transport.label,
        options = SshTransport.entries,
        optionLabel = { it.label },
        onSelect = { t ->
            onUpdateProfile {
                val cur = it.ssh
                val switchingToSlowDns = t == SshTransport.SLOWDNS && cur.transport != SshTransport.SLOWDNS
                it.copy(
                    ssh = cur.copy(
                        transport = t,
                        port = if (switchingToSlowDns && cur.port == 22) 443 else cur.port,
                        dnsServer = if (switchingToSlowDns && (cur.dnsServer.isBlank() || cur.dnsServer == "8.8.8.8")) "Auto" else cur.dnsServer,
                        dnsType = if (switchingToSlowDns) SlowDnsRecordType.AUTO else cur.dnsType
                    )
                )
            }
        }
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = s.host,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(host = v.replace("\n", ""))) } },
            label = { Text("Host SSH (Domain / IP)") },
            singleLine = true,
            modifier = Modifier.weight(2f)
        )
        NumericTextField(
            value = s.port,
            defaultValue = if (s.transport == SshTransport.SLOWDNS) 443 else 22,
            onValueChange = { p -> onUpdateProfile { it.copy(ssh = it.ssh.copy(port = p)) } },
            label = "Port",
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

    if (s.transport == SshTransport.SLOWDNS) {
        CompactDropdown(
            label = "Tipe DNS",
            selectedLabel = s.dnsType.label,
            options = SlowDnsRecordType.entries.filter { it.visibleInUi },
            optionLabel = { it.label },
            onSelect = { dt -> onUpdateProfile { it.copy(ssh = it.ssh.copy(dnsType = dt)) } }
        )
        OutlinedTextField(
            value = s.dnsServer,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(dnsServer = v.replace("\n", ""))) } },
            label = { Text("DNS Resolver (opsional)") },
            placeholder = { Text("Auto (atau isi manual)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.nameserver,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(nameserver = v.replace("\n", ""))) } },
            label = { Text("NS (Nameserver)") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.publicKey,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(publicKey = v.replace("\n", ""))) } },
            label = { Text("Kunci Publik (Public Key)") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            onClick = {
                if (isScanningDns) return@OutlinedButton
                isScanningDns = true
                scanError = null
                scanCandidates = emptyList()
                scope.launch {
                    val underlying = VexotunVpnService.detectUnderlyingDnsServers(context)
                    val payload = CoreSshPayload(
                        host = s.host.trim(),
                        port = if (s.port > 0) s.port else 443,
                        username = s.username,
                        password = s.password,
                        privateKey = s.privateKey,
                        transport = s.transport.wireValue,
                        proxyHost = "",
                        proxyPort = 8080,
                        sni = "",
                        payload = "",
                        wsPath = "",
                        wsHost = "",
                        dnsServer = s.dnsServer.trim().ifBlank { "Auto" },
                        fallbackDns = underlying,
                        dnsType = "AUTO",
                        nameserver = s.nameserver.trim(),
                        publicKey = s.publicKey.trim()
                    )
                    val rawJson = withContext(Dispatchers.IO) {
                        TunnelState.scanSlowDns(scanJson.encodeToString(payload))
                    }
                    val resp = runCatching { scanJson.decodeFromString<SlowDnsScanResponseUi>(rawJson) }
                        .getOrElse { SlowDnsScanResponseUi(ok = false, error = it.message ?: "Parse gagal") }
                    isScanningDns = false
                    if (resp.ok) {
                        scanCandidates = resp.candidates.take(8)
                    } else {
                        scanError = resp.error.ifBlank { "Tidak ada jalur DNS yang merespons" }
                    }
                }
            },
            enabled = !isScanningDns && s.nameserver.isNotBlank() && s.publicKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.NetworkPing, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (isScanningDns) "Memindai DNS Kartu..." else "Tes / Scan DNS Kartu (11 QTYPE)")
        }

        if (scanError != null) {
            Text(
                scanError!!,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
        }
        if (scanCandidates.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Jalur Tembus (${scanCandidates.size}) — ketuk untuk pakai:",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    scanCandidates.forEachIndexed { idx, cand ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val mapped = SlowDnsRecordType.entries.firstOrNull {
                                        it.wireValue.equals(cand.qtype, ignoreCase = true)
                                    } ?: SlowDnsRecordType.AUTO
                                    onUpdateProfile {
                                        it.copy(
                                            ssh = it.ssh.copy(
                                                dnsServer = cand.resolver,
                                                dnsType = mapped
                                            )
                                        )
                                    }
                                    Toast.makeText(
                                        context,
                                        "Dipakai: ${cand.qtype} via ${cand.resolver}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "#${idx + 1} ${cand.qtype} • ${cand.resolver}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "MTU ${cand.mtu} (${cand.rttMs}ms)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    if (s.transport == SshTransport.SSL || s.transport == SshTransport.WSS) {
        OutlinedTextField(
            value = s.sni,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(sni = v.replace("\n", ""))) } },
            label = { Text("SNI") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (s.transport == SshTransport.WS || s.transport == SshTransport.WSS) {
        OutlinedTextField(
            value = s.wsPath,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(wsPath = v.replace("\n", ""))) } },
            label = { Text("WS Path") },
            singleLine = false,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.wsHost,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(wsHost = v.replace("\n", ""))) } },
            label = { Text("WS Host") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (s.transport != SshTransport.DIRECT && s.transport != SshTransport.SLOWDNS) {
        Card(
            onClick = { showAdvanced = !showAdvanced },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
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
                        label = { Text("Proxy Host") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    NumericTextField(
                        value = s.proxyPort,
                        defaultValue = 8080,
                        onValueChange = { p -> onUpdateProfile { it.copy(ssh = it.ssh.copy(proxyPort = p)) } },
                        label = "Port",
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

internal fun parseSshAccountPaste(raw: String): SshProfile? {
    val text = raw.trim()
    if (text.isBlank()) return null

    // 1) host:port@user:pass or ssh://user:pass@host:port
    val clean = text.removePrefix("ssh://").trim()
    if (clean.contains("@") && !clean.contains("\n")) {
        val parts = clean.split("@", limit = 2)
        val left = parts[0].split(":", limit = 2)
        val right = parts[1].split(":", limit = 2)
        if (left.size == 2 && right.size == 2) {
            val leftPort = left[1].toIntOrNull()
            val rightPort = right[1].toIntOrNull()
            return if (leftPort != null) {
                SshProfile(host = left[0].trim(), port = leftPort, username = right[0].trim(), password = right[1].trim())
            } else if (rightPort != null) {
                SshProfile(host = right[0].trim(), port = rightPort, username = left[0].trim(), password = left[1].trim())
            } else null
        }
    }

    // 2) Multi-line key: value format from VPS panels
    var host = ""
    var port = 0
    var user = ""
    var pass = ""
    var ns = ""
    var pubkey = ""
    text.lines().forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) {
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when {
                key in listOf("host", "server", "ip", "domain", "address") -> if (host.isBlank()) host = value
                key == "port" -> if (port == 0) port = value.filter(Char::isDigit).toIntOrNull() ?: 0
                key in listOf("user", "username") -> if (user.isBlank()) user = value
                key in listOf("pass", "password", "sandi") -> if (pass.isBlank()) pass = value
                key in listOf("ns", "nameserver", "slowdns ns") -> if (ns.isBlank()) ns = value
                key in listOf("pubkey", "public key", "publickey", "key") -> if (pubkey.isBlank()) pubkey = value
            }
        }
    }
    return if (host.isNotBlank() || user.isNotBlank() || ns.isNotBlank()) {
        val isSd = ns.isNotBlank() || pubkey.isNotBlank()
        SshProfile(
            host = host,
            port = if (port > 0) port else 443,
            username = user,
            password = pass,
            transport = if (isSd) SshTransport.SLOWDNS else SshTransport.HTTP_PROXY,
            dnsServer = if (isSd) "Auto" else "8.8.8.8",
            dnsType = if (isSd) SlowDnsRecordType.AUTO else SlowDnsRecordType.TXT,
            nameserver = ns,
            publicKey = pubkey
        )
    } else {
        null
    }
}

