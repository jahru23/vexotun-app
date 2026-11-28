package com.vexotun.app.ui.main

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vexotun.app.model.SlowDnsRecordType
import com.vexotun.app.model.SshProfile
import com.vexotun.app.model.SshTransport
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.TunnelProfile
import com.vexotun.app.model.XrayProtocol
import com.vexotun.app.model.XraySecurityMode
import com.vexotun.app.model.XrayTransport

@Composable
internal fun ConfigEditorPage(
    profile: TunnelProfile,
    isLockedByVpn: Boolean = false,
    dnsScanState: SlowDnsScanState = SlowDnsScanState(),
    onScanDns: (SshProfile) -> Unit = {},
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
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
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
            label = { Text("Nama Profil") },
            enabled = canEdit,
            readOnly = !canEdit,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (profile.mode == TunnelMode.XRAY) {
            XrayProfileEditor(profile = profile, onUpdateProfile = guardedUpdate)
        } else {
            SshProfileEditor(
                profile = profile,
                dnsScanState = dnsScanState,
                onScanDns = onScanDns,
                onUpdateProfile = guardedUpdate
            )
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp)
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
        } else if (value <= 0 && text.isNotEmpty() && currentParsed != 0) {
            text = ""
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
private fun EditorSection(
    title: String,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun SwitchOptionRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun XrayProfileEditor(
    profile: TunnelProfile,
    onUpdateProfile: ((TunnelProfile) -> TunnelProfile) -> Unit
) {
    val x = profile.xray
    val secMode = x.effectiveSecurityMode()

    EditorSection(title = "Server & Akun", initiallyExpanded = true) {
        CompactDropdown(
            label = "Protokol",
            selectedLabel = x.protocol.label,
            options = XrayProtocol.entries,
            optionLabel = { it.label },
            onSelect = { proto ->
                onUpdateProfile { cur ->
                    val cx = cur.xray
                    val nextSec = if (proto != XrayProtocol.VLESS && cx.effectiveSecurityMode() == XraySecurityMode.REALITY) {
                        XraySecurityMode.TLS
                    } else {
                        cx.securityMode
                    }
                    val nextTls = if (nextSec != null) nextSec == XraySecurityMode.TLS else cx.tls
                    val nextFlow = if (proto != XrayProtocol.VLESS) "" else cx.flow
                    cur.copy(
                        xray = cx.copy(
                            protocol = proto,
                            securityMode = nextSec,
                            tls = nextTls,
                            flow = nextFlow
                        )
                    )
                }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = x.address,
                onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(address = v.trim())) } },
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
        if (x.protocol == XrayProtocol.VLESS) {
            val flowOptions = listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
            CompactDropdown(
                label = "Flow",
                selectedLabel = x.flow.ifBlank { "Tidak ada" },
                options = flowOptions,
                optionLabel = { it.ifBlank { "Tidak ada" } },
                onSelect = { f -> onUpdateProfile { it.copy(xray = it.xray.copy(flow = f)) } }
            )
        }
        if (x.protocol == XrayProtocol.VMESS) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactDropdown(
                    label = "Security / Encryption",
                    selectedLabel = x.security.ifBlank { "auto" },
                    options = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"),
                    optionLabel = { it },
                    onSelect = { s -> onUpdateProfile { it.copy(xray = it.xray.copy(security = s)) } },
                    modifier = Modifier.weight(2f)
                )
                NumericTextField(
                    value = x.alterId,
                    defaultValue = 0,
                    onValueChange = { aid -> onUpdateProfile { it.copy(xray = it.xray.copy(alterId = aid)) } },
                    label = "AlterId",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (x.protocol == XrayProtocol.SHADOWSOCKS) {
            OutlinedTextField(
                value = x.security,
                onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(security = v)) } },
                label = { Text("Method / Cipher") },
                placeholder = { Text("aes-128-gcm / chacha20-ietf-poly1305") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    EditorSection(title = "Transport", initiallyExpanded = true) {
        CompactDropdown(
            label = "Transport",
            selectedLabel = x.network.label,
            options = XrayTransport.entries,
            optionLabel = { it.label },
            onSelect = { net -> onUpdateProfile { it.copy(xray = it.xray.copy(network = net)) } }
        )
        if (x.network != XrayTransport.TCP || x.headerType.equals("http", true)) {
            OutlinedTextField(
                value = x.path,
                onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(path = v)) } },
                label = { Text(if (x.network == XrayTransport.GRPC) "Service Name" else "Path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = x.host,
            onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(host = v.replace("\n", ""))) } },
            label = { Text(if (x.network == XrayTransport.GRPC) "Authority (opsional)" else "Host Header") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (x.network == XrayTransport.TCP) {
            CompactDropdown(
                label = "TCP Header Type",
                selectedLabel = if (x.headerType.equals("http", true)) "http (HTTP Obfuscation)" else "none",
                options = listOf("", "http"),
                optionLabel = { if (it == "http") "http (HTTP Obfuscation)" else "none" },
                onSelect = { ht -> onUpdateProfile { it.copy(xray = it.xray.copy(headerType = ht)) } }
            )
        }
        if (x.network == XrayTransport.GRPC) {
            SwitchOptionRow(
                label = "gRPC Multi Mode",
                checked = x.grpcMultiMode,
                onCheckedChange = { mm -> onUpdateProfile { it.copy(xray = it.xray.copy(grpcMultiMode = mm)) } }
            )
        }
        if (x.network == XrayTransport.XHTTP) {
            CompactDropdown(
                label = "XHTTP Mode",
                selectedLabel = x.xhttpMode.ifBlank { "auto (default)" },
                options = listOf("", "auto", "packet-up", "stream-up", "stream-one"),
                optionLabel = { it.ifBlank { "auto (default)" } },
                onSelect = { m -> onUpdateProfile { it.copy(xray = it.xray.copy(xhttpMode = m)) } }
            )
        }
    }

    EditorSection(title = "Keamanan (TLS / Reality)", initiallyExpanded = true) {
        val allowedModes = if (x.protocol == XrayProtocol.VLESS) {
            XraySecurityMode.entries
        } else {
            listOf(XraySecurityMode.NONE, XraySecurityMode.TLS)
        }
        CompactDropdown(
            label = "Mode Keamanan",
            selectedLabel = secMode.label,
            options = allowedModes,
            optionLabel = { it.label },
            onSelect = { mode ->
                onUpdateProfile {
                    it.copy(
                        xray = it.xray.copy(
                            securityMode = mode,
                            tls = mode == XraySecurityMode.TLS
                        )
                    )
                }
            }
        )
        if (secMode != XraySecurityMode.NONE) {
            OutlinedTextField(
                value = x.sni,
                onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(sni = v.replace("\n", ""))) } },
                label = { Text("SNI") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            val fpOptions = listOf("", "chrome", "firefox", "safari", "ios", "android", "random", "randomized")
            CompactDropdown(
                label = "uTLS Fingerprint",
                selectedLabel = x.fingerprint.ifBlank { "chrome (default)" },
                options = fpOptions,
                optionLabel = { it.ifBlank { "chrome (default)" } },
                onSelect = { fp -> onUpdateProfile { it.copy(xray = it.xray.copy(fingerprint = fp)) } }
            )
        }
        if (secMode == XraySecurityMode.TLS) {
            OutlinedTextField(
                value = x.alpn,
                onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(alpn = v.replace("\n", ""))) } },
                label = { Text("ALPN (opsional, pisah koma)") },
                placeholder = { Text("h2,http/1.1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = x.verifyPeerCertByName,
                onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(verifyPeerCertByName = v.trim())) } },
                label = { Text("Verify Cert Name / vcn (opsional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = x.pinnedPeerCertSha256,
                onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(pinnedPeerCertSha256 = v.trim())) } },
                label = { Text("Pinned Cert SHA-256 / pcs (opsional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SwitchOptionRow(
                label = "Allow Insecure (Legacy)",
                subtitle = "Terima sertifikat self-signed atau SNI mismatch",
                checked = x.allowInsecure,
                onCheckedChange = { allow -> onUpdateProfile { it.copy(xray = it.xray.copy(allowInsecure = allow)) } }
            )
        }
        if (secMode == XraySecurityMode.REALITY) {
            OutlinedTextField(
                value = x.realityPublicKey,
                onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(realityPublicKey = v.trim())) } },
                label = { Text("Public Key (pbk)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = x.realityShortId,
                    onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(realityShortId = v.trim())) } },
                    label = { Text("Short ID (sid)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = x.realitySpiderX,
                    onValueChange = { v -> onUpdateProfile { it.copy(xray = it.xray.copy(realitySpiderX = v)) } },
                    label = { Text("SpiderX (spx)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    EditorSection(title = "Mux & Payload Kustom", initiallyExpanded = x.useCustomPayload || x.muxEnabled) {
        if (x.protocol != XrayProtocol.SHADOWSOCKS &&
            x.flow.isBlank() &&
            x.network != XrayTransport.GRPC &&
            x.network != XrayTransport.XHTTP
        ) {
            SwitchOptionRow(
                label = "Xray Mux (Multi-koneksi)",
                subtitle = "Aktifkan hanya jika server mendukung Mux",
                checked = x.muxEnabled,
                onCheckedChange = { checked -> onUpdateProfile { it.copy(xray = it.xray.copy(muxEnabled = checked)) } }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        SwitchOptionRow(
            label = "Payload & Proxy Kustom",
            checked = x.useCustomPayload,
            onCheckedChange = { checked -> onUpdateProfile { it.copy(xray = it.xray.copy(useCustomPayload = checked)) } }
        )

        if (x.useCustomPayload) {
            val hasProxy = x.proxyHost.isNotBlank() && x.proxyPort > 0
            val compatWarning = when {
                secMode == XraySecurityMode.REALITY ->
                    "Custom Payload tidak kompatibel dengan Xray Reality."
                (x.network == XrayTransport.WS || x.network == XrayTransport.HTTPUPGRADE) &&
                    !hasProxy && x.payload.contains("Upgrade: websocket", ignoreCase = true) ->
                    "Transport ${x.network.label} sudah mengirim HTTP Upgrade sendiri. Isi Proxy Host (CONNECT) atau hapus header Upgrade."
                secMode == XraySecurityMode.TLS &&
                    !hasProxy && !x.payload.contains("CONNECT ", ignoreCase = true) ->
                    "Custom Payload pada mode TLS membutuhkan Proxy Host + payload CONNECT (atau gunakan Non-TLS)."
                else -> null
            }
            if (compatWarning != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                ) {
                    Text(
                        compatWarning,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
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
            val tags = listOf("[crlf]", "[host]", "[sni]", "[port]", "[host_port]", "[split]", "[delay_split]", "[ua]", "[protocol]")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEach { tag ->
                    SuggestionChip(
                        onClick = { onUpdateProfile { it.copy(xray = it.xray.copy(payload = it.xray.payload + tag)) } },
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
}

@Composable
internal fun SshProfileEditor(
    profile: TunnelProfile,
    dnsScanState: SlowDnsScanState,
    onScanDns: (SshProfile) -> Unit,
    onUpdateProfile: ((TunnelProfile) -> TunnelProfile) -> Unit
) {
    val context = LocalContext.current
    val s = profile.ssh

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
        shape = RoundedCornerShape(8.dp)
    ) {
        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Tempel Akun SSH")
    }

    EditorSection(title = "Server & Akun SSH", initiallyExpanded = true) {
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
    }

    if (s.transport == SshTransport.SLOWDNS) {
        EditorSection(title = "SlowDNS & Scan", initiallyExpanded = true) {
            val dnsModes = listOf("auto" to "Auto (VexDNS + dnstt)", "dnstt" to "Standar dnstt", "vexdns" to "VexDNS")
            CompactDropdown(
                label = "Protokol SlowDNS",
                selectedLabel = dnsModes.firstOrNull { it.first == s.slowDnsMode }?.second ?: "Auto (VexDNS + dnstt)",
                options = dnsModes,
                optionLabel = { it.second },
                onSelect = { (k, _) -> onUpdateProfile { it.copy(ssh = it.ssh.copy(slowDnsMode = k)) } }
            )
            CompactDropdown(
                label = "Tipe Record DNS",
                selectedLabel = s.dnsType.label,
                options = SlowDnsRecordType.entries.filter { it.visibleInUi },
                optionLabel = { it.label },
                onSelect = { dt -> onUpdateProfile { it.copy(ssh = it.ssh.copy(dnsType = dt)) } }
            )
            OutlinedTextField(
                value = s.dnsServer,
                onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(dnsServer = v.replace("\n", ""))) } },
                label = { Text("DNS Resolver (opsional)") },
                placeholder = { Text("Auto (atau pisah koma: 1.1.1.1,8.8.8.8)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.nameserver,
                onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(nameserver = v.replace("\n", ""))) } },
                label = { Text("NS (Nameserver)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.publicKey,
                onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(publicKey = v.replace("\n", ""))) } },
                label = { Text("Public Key") },
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = { onScanDns(s) },
                enabled = !dnsScanState.isScanning && s.nameserver.isNotBlank() && s.publicKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.NetworkPing, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (dnsScanState.isScanning) "Memindai DNS..." else "Tes / Scan Resolver DNS")
            }
            if (dnsScanState.error != null) {
                Text(dnsScanState.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (dnsScanState.candidates.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Jalur Tembus (${dnsScanState.candidates.size}), ketuk untuk pakai:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                dnsScanState.candidates.forEachIndexed { idx, cand ->
                    val modeTag = if (cand.mode.isNotBlank()) " [${cand.mode}]" else ""
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
                                            dnsType = mapped,
                                            slowDnsMode = cand.mode.ifBlank { it.ssh.slowDnsMode }
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
                            "#${idx + 1} ${cand.qtype}$modeTag • ${cand.resolver}",
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

    if (s.transport == SshTransport.SSL || s.transport == SshTransport.WS || s.transport == SshTransport.WSS) {
        EditorSection(title = "Opsi SSL / WebSocket", initiallyExpanded = true) {
            if (s.transport == SshTransport.SSL || s.transport == SshTransport.WSS) {
                OutlinedTextField(
                    value = s.sni,
                    onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(sni = v.replace("\n", ""))) } },
                    label = { Text("SNI") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (s.transport == SshTransport.WS || s.transport == SshTransport.WSS) {
                OutlinedTextField(
                    value = s.wsPath,
                    onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(wsPath = v.replace("\n", ""))) } },
                    label = { Text("WS Path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = s.wsHost,
                    onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(wsHost = v.replace("\n", ""))) } },
                    label = { Text("WS Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SwitchOptionRow(
                    label = "Mode Raw HTTP 101",
                    subtitle = "Paksa mode Raw HTTP Upgrade (script ws-dropbear/python)",
                    checked = s.wsRawUpgrade,
                    onCheckedChange = { raw -> onUpdateProfile { it.copy(ssh = it.ssh.copy(wsRawUpgrade = raw)) } }
                )
            }
        }
    }

    if (s.transport != SshTransport.SLOWDNS) {
        EditorSection(title = "Proxy, Payload & UDP", initiallyExpanded = s.transport == SshTransport.HTTP_PROXY) {
            if (s.transport != SshTransport.DIRECT) {
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

                val tags = listOf("[crlf]", "[host]", "[sni]", "[port]", "[host_port]", "[split]", "[delay_split]", "[ua]", "[netData]")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tags.forEach { tag ->
                        SuggestionChip(
                            onClick = { onUpdateProfile { it.copy(ssh = it.ssh.copy(payload = it.ssh.payload + tag)) } },
                            label = { Text(tag, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = s.payload,
                    onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(payload = v)) } },
                    label = { Text("Payload HTTP") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            NumericTextField(
                value = s.udpgwPort,
                defaultValue = 0,
                onValueChange = { p -> onUpdateProfile { it.copy(ssh = it.ssh.copy(udpgwPort = p)) } },
                label = "Port UDPGW (0 = mati, mis. 7300)",
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.privateKey,
                onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(privateKey = v)) } },
                label = { Text("Private Key PEM (opsional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

internal fun parseSshAccountPaste(raw: String): SshProfile? {
    val text = raw.trim()
    if (text.isBlank()) return null

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
