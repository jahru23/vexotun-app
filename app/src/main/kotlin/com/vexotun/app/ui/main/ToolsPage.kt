package com.vexotun.app.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

    var ipLoading by remember { mutableStateOf(false) }
    var ipResult by remember { mutableStateOf<IpInfoResult?>(null) }

    var bugHost by remember { mutableStateOf("") }
    var bugPort by remember { mutableStateOf("443") }
    var bugSni by remember { mutableStateOf("") }
    var bugUseTls by remember { mutableStateOf(true) }
    var bugLoading by remember { mutableStateOf(false) }
    var bugResult by remember { mutableStateOf<HostCheckResult?>(null) }

    var dnsDomain by remember { mutableStateOf("") }
    var dnsType by remember { mutableStateOf("NS") }
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
        // 1. Cek IP & ISP
        ToolCard(
            title = "Cek IP & ISP",
            subtitle = if (isVpnConnected) "Aktif: dicek lewat tunnel VPN" else "VPN mati: dicek lewat jaringan langsung",
            icon = Icons.Default.Public
        ) {
            Button(
                onClick = {
                    if (ipLoading) return@Button
                    ipLoading = true
                    scope.launch {
                        ipResult = NetworkTools.fetchIpInfo(isVpnConnected, socksPort)
                        ipLoading = false
                    }
                },
                enabled = !ipLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (ipLoading) "Memeriksa IP..." else "Cek IP Publik Sekarang")
            }

            ipResult?.let { res ->
                Spacer(Modifier.height(8.dp))
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
                    Text(
                        res.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // 2. Host / Bug & SNI Checker
        ToolCard(
            title = "Host / Bug & SNI Checker",
            subtitle = "Uji respon TCP, TLS SNI, dan header HTTP langsung di jaringan Anda",
            icon = Icons.Default.Security
        ) {
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
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (bugLoading) "Menguji Host..." else "Uji Host / SNI")
            }

            bugResult?.let { res ->
                Spacer(Modifier.height(8.dp))
                if (res.ok) {
                    Text(
                        buildString {
                            appendLine("${res.statusLine}")
                            val tlsInfo = if (res.tlsMs >= 0) " • TLS ${res.tlsMs} ms (${res.tlsProtocol})" else ""
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

        // 3. DNS Lookup
        ToolCard(
            title = "DNS Lookup",
            subtitle = "Periksa record NS, A, AAAA, TXT, atau CNAME domain",
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
            Button(
                onClick = {
                    if (dnsLoading) return@Button
                    dnsLoading = true
                    scope.launch {
                        dnsResult = NetworkTools.lookupDns(dnsDomain, dnsType, isVpnConnected, socksPort)
                        dnsLoading = false
                    }
                },
                enabled = !dnsLoading && dnsDomain.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (dnsLoading) "Mencari DNS..." else "Cek Record $dnsType")
            }

            dnsResult?.let { res ->
                Spacer(Modifier.height(8.dp))
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

        // 4. Tes Kecepatan Ringkas
        ToolCard(
            title = "Tes Kecepatan Ringkas",
            subtitle = if (isVpnConnected) "Mengukur throughput tunnel VPN (maks 2 MB)" else "Mengukur throughput jaringan langsung (maks 2 MB)",
            icon = Icons.Default.Speed
        ) {
            Button(
                onClick = {
                    if (speedLoading) return@Button
                    speedLoading = true
                    scope.launch {
                        speedResult = NetworkTools.runSpeedCheck(isVpnConnected, socksPort)
                        speedLoading = false
                    }
                },
                enabled = !speedLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (speedLoading) "Menguji Kecepatan..." else "Mulai Tes Unduh (2 MB)")
            }

            speedResult?.let { res ->
                Spacer(Modifier.height(8.dp))
                if (res.ok) {
                    val mbStr = String.format(Locale.US, "%.2f", res.mbps)
                    val kb = res.bytes / 1024L
                    Text(
                        "Kecepatan: $mbStr Mbps • $kb KB dalam ${res.elapsedMs} ms (${if (res.viaTunnel) "Tunnel" else "Langsung"})",
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

        // 5. Asisten Background & Baterai
        ToolCard(
            title = "Asisten Background & Baterai",
            subtitle = "Mencegah sistem (terutama MIUI/HyperOS) mematikan VPN saat pindah aplikasi",
            icon = Icons.Default.BatteryChargingFull
        ) {
            Text(
                if (batteryIgnored) "Status Baterai: Bebas pembatasan (OK)" else "Status Baterai: Masih dibatasi sistem",
                style = MaterialTheme.typography.bodySmall,
                color = if (batteryIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        openBatteryOptimizationSettings(context)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            batteryIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Izin Baterai")
                }
                OutlinedButton(
                    onClick = { openAutostartOrAppDetails(context) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Autostart / Info")
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
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
