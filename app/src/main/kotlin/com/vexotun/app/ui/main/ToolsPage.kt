package com.vexotun.app.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vexotun.app.data.DnsLookupResult
import com.vexotun.app.data.HostCheckResult
import com.vexotun.app.data.IpInfoResult
import com.vexotun.app.data.NetworkTools
import com.vexotun.app.data.SpeedCheckResult
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ToolsPage(
    socksPort: Int,
    isVpnConnected: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bugHost by rememberSaveable { mutableStateOf("") }
    var bugPort by rememberSaveable { mutableStateOf("443") }
    var bugSni by rememberSaveable { mutableStateOf("") }
    var bugUseTls by rememberSaveable { mutableStateOf(true) }
    var bugLoading by remember { mutableStateOf(false) }
    var bugResult by remember { mutableStateOf<HostCheckResult?>(null) }

    var ipLoading by remember { mutableStateOf(false) }
    var ipResult by remember { mutableStateOf<IpInfoResult?>(null) }

    var dnsDomain by rememberSaveable { mutableStateOf("") }
    var dnsType by rememberSaveable { mutableStateOf("NS") }
    var dnsLoading by remember { mutableStateOf(false) }
    var dnsResult by remember { mutableStateOf<DnsLookupResult?>(null) }

    var speedLoading by remember { mutableStateOf(false) }
    var speedResult by remember { mutableStateOf<SpeedCheckResult?>(null) }

    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var batteryIgnored by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            } else {
                true
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Primary Block: Host / Bug & SNI Checker
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Host / Bug & SNI Checker",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "Uji TCP, TLS/SNI (termasuk cert non-match), dan respon HTTP langsung di jaringan Anda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bugHost,
                        onValueChange = { bugHost = it },
                        label = { Text("Host / Bug") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = bugPort,
                        onValueChange = { v -> bugPort = v.filter { it.isDigit() }.take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(92.dp)
                    )
                }
                OutlinedTextField(
                    value = bugSni,
                    onValueChange = { bugSni = it },
                    label = { Text("SNI / Host Header (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Gunakan TLS / SSL", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = bugUseTls,
                        onCheckedChange = {
                            bugUseTls = it
                            if (it && bugPort == "80") bugPort = "443"
                            if (!it && bugPort == "443") bugPort = "80"
                        }
                    )
                }
                Button(
                    onClick = {
                        if (bugLoading) return@Button
                        bugLoading = true
                        scope.launch {
                            val portInt = bugPort.toIntOrNull() ?: if (bugUseTls) 443 else 80
                            bugResult = NetworkTools.checkBugHost(bugHost, portInt, bugUseTls, bugSni)
                            bugLoading = false
                        }
                    },
                    enabled = !bugLoading && bugHost.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (bugLoading) "Menguji Host..." else "Uji Host / SNI", fontWeight = FontWeight.SemiBold)
                }

                bugResult?.let { res ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (res.ok) {
                        Text(
                            buildString {
                                appendLine(res.statusLine)
                                val tlsInfo = if (res.tlsMs >= 0) {
                                    val trustNote = if (res.tlsVerified) "Cert Valid" else "Cert Tidak Cocok / Self-Signed"
                                    " • TLS ${res.tlsMs} ms (${res.tlsProtocol}, $trustNote)"
                                } else ""
                                appendLine("TCP ${res.tcpMs} ms$tlsInfo")
                                if (res.certSubject.isNotBlank()) appendLine("Cert: ${res.certSubject}")
                                res.headers.forEach { (k, v) -> appendLine("$k: $v") }
                            }.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            res.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Compact Expandable Flat Utilities
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                ExpandableUtilityRow(
                    title = "Cek IP & ISP",
                    subtitle = if (isVpnConnected) "Jalur aktif: Tunnel VPN" else "Jalur aktif: Langsung",
                    icon = Icons.Default.Public,
                    initiallyExpanded = true
                ) {
                    OutlinedButton(
                        onClick = {
                            if (ipLoading) return@OutlinedButton
                            ipLoading = true
                            scope.launch {
                                ipResult = NetworkTools.fetchIpInfo(isVpnConnected, socksPort)
                                ipLoading = false
                            }
                        },
                        enabled = !ipLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (ipLoading) "Memeriksa IP..." else "Cek IP Publik Sekarang")
                    }
                    ipResult?.let { res ->
                        if (res.ok) {
                            val loc = listOf(res.city, res.country).filter { it.isNotBlank() }.joinToString(", ")
                            Text(
                                buildString {
                                    appendLine("IP: ${res.ip} (${if (res.viaTunnel) "Tunnel" else "Langsung"})")
                                    if (loc.isNotBlank()) appendLine("Lokasi: $loc")
                                    if (res.isp.isNotBlank()) appendLine("ISP: ${res.isp}")
                                    if (res.asn.isNotBlank()) append("ASN: ${res.asn}")
                                }.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text(res.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ExpandableUtilityRow(
                    title = "DNS Lookup",
                    subtitle = "Periksa record NS, A, AAAA, TXT, atau CNAME",
                    icon = Icons.Default.Dns
                ) {
                    OutlinedTextField(
                        value = dnsDomain,
                        onValueChange = { dnsDomain = it },
                        label = { Text("Domain / Nameserver") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("NS", "A", "AAAA", "TXT", "CNAME").forEach { type ->
                            FilterChip(
                                selected = dnsType == type,
                                onClick = { dnsType = type },
                                label = { Text(type) }
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (dnsLoading) return@OutlinedButton
                            dnsLoading = true
                            scope.launch {
                                dnsResult = NetworkTools.lookupDns(dnsDomain, dnsType, isVpnConnected, socksPort)
                                dnsLoading = false
                            }
                        },
                        enabled = !dnsLoading && dnsDomain.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (dnsLoading) "Mencari DNS..." else "Cek Record $dnsType")
                    }
                    dnsResult?.let { res ->
                        if (res.records.isNotEmpty()) {
                            Text(
                                res.records.joinToString("\n") { "${it.data} (TTL ${it.ttl}s)" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text(
                                res.error.ifBlank { "Tidak ada hasil" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ExpandableUtilityRow(
                    title = "Tes Kecepatan Unduh",
                    subtitle = if (isVpnConnected) "Mengukur throughput tunnel (2 MB)" else "Mengukur throughput langsung (2 MB)",
                    icon = Icons.Default.Speed
                ) {
                    OutlinedButton(
                        onClick = {
                            if (speedLoading) return@OutlinedButton
                            speedLoading = true
                            scope.launch {
                                speedResult = NetworkTools.runSpeedCheck(isVpnConnected, socksPort)
                                speedLoading = false
                            }
                        },
                        enabled = !speedLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (speedLoading) "Menguji Kecepatan..." else "Mulai Tes Unduh (2 MB)")
                    }
                    speedResult?.let { res ->
                        if (res.ok) {
                            val mbStr = String.format(Locale.US, "%.2f", res.mbps)
                            val kb = res.bytes / 1024L
                            Text(
                                "Kecepatan: $mbStr Mbps • $kb KB dalam ${res.elapsedMs} ms (${if (res.viaTunnel) "Tunnel" else "Langsung"})",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text(res.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ExpandableUtilityRow(
                    title = "Asisten Background & Baterai",
                    subtitle = if (batteryIgnored) "Status: Bebas pembatasan" else "Status: Masih dibatasi sistem",
                    icon = Icons.Default.BatteryChargingFull
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                openBatteryOptimizationSettings(context)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    batteryIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Izin Baterai")
                        }
                        OutlinedButton(
                            onClick = { openAutostartOrAppDetails(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Autostart / Info")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableUtilityRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val req = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(req) }.isSuccess) return
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(list) }.isSuccess) return
    }
    openAppDetails(context)
}

private fun openAutostartOrAppDetails(context: Context) {
    val candidates = listOf(
        Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        ),
        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    openAppDetails(context)
}

private fun openAppDetails(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
