package com.vexotun.app.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.ui.theme.LocalIsDarkTheme
import com.vexotun.app.ui.theme.LogConsoleBg
import com.vexotun.app.ui.theme.LogErrorFg
import com.vexotun.app.ui.theme.LogInfoFg
import com.vexotun.app.ui.theme.LogWarnFg
import com.vexotun.app.ui.theme.StatusConnectedFgDark
import com.vexotun.app.ui.theme.StatusConnectedFgLight
import com.vexotun.app.ui.theme.TabularTelemetryStyle
import com.vexotun.app.vpn.ConnectionStatus
import com.vexotun.app.vpn.TrafficStatsState
import com.vexotun.app.vpn.TunnelState
import com.vexotun.app.vpn.VpnRuntimeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TunnelPage(
    config: VexotunConfig,
    runtime: VpnRuntimeState,
    trafficStateFlow: StateFlow<TrafficStatsState>,
    isPinging: Boolean,
    onSelectProfile: (String) -> Unit,
    onGoToConfig: () -> Unit,
    onOpenImport: () -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPingClick: () -> Unit,
    onClearLogs: () -> Unit
) {
    val active = config.activeProfile()
    val isActive = runtime.status == ConnectionStatus.CONNECTED || runtime.status == ConnectionStatus.CONNECTING
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    var logFilter by rememberSaveable { mutableStateOf("ALL") }
    var elapsedSec by remember { mutableLongStateOf(0L) }

    LaunchedEffect(runtime.status, runtime.connectedAtElapsedMs) {
        if (runtime.status == ConnectionStatus.CONNECTED && runtime.connectedAtElapsedMs > 0L) {
            while (true) {
                elapsedSec = ((SystemClock.elapsedRealtime() - runtime.connectedAtElapsedMs) / 1000L).coerceAtLeast(0L)
                delay(1000L)
            }
        } else {
            elapsedSec = 0L
        }
    }

    val visibleLogs = remember(runtime.logs, logFilter) {
        when (logFilter) {
            "INFO" -> runtime.logs.filter { it.level == "INFO" }
            "ERROR" -> runtime.logs.filter { it.level == "WARN" || it.level == "ERROR" }
            else -> runtime.logs
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (active == null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        "Belum Ada Profil",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Buat profil Xray atau SSH di halaman Config, atau import link/.vxt untuk mulai menghubungkan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onGoToConfig,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Buat Config", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onOpenImport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import Config")
                    }
                }
            }
        } else {
            // Zone 1: Profile Switcher + Connection Control
            var dropdownExpanded by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = !isActive) { dropdownExpanded = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ModeBadge(active.mode)
                            Text(
                                active.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (active.isLocked) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "Terkunci",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            active.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        active.lockInfo?.let { lock ->
                            val meta = buildList {
                                if (lock.author.isNotBlank()) add("oleh ${lock.author}")
                                if (lock.expiresAtMs > 0L) {
                                    val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                    add("Exp ${fmt.format(Date(lock.expiresAtMs))}")
                                }
                                if (lock.note.isNotBlank()) add(lock.note)
                            }.joinToString(" • ")
                            if (meta.isNotBlank()) {
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (!isActive) {
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = "Ganti Profil",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = dropdownExpanded && !isActive,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    config.profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ModeBadge(profile.mode)
                                    Column {
                                        Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(
                                            profile.summary(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSelectProfile(profile.id)
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val title = when (runtime.status) {
                            ConnectionStatus.CONNECTED -> "Tunnel Aktif"
                            ConnectionStatus.CONNECTING -> runtime.statusMessage.ifBlank { "Menghubungkan..." }
                            ConnectionStatus.ERROR -> "Koneksi Gagal"
                            ConnectionStatus.DISCONNECTED -> "Siap Menghubungkan"
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (runtime.status == ConnectionStatus.CONNECTED) {
                            val h = elapsedSec / 3600L
                            val m = (elapsedSec % 3600L) / 60L
                            val s = elapsedSec % 60L
                            Text(
                                text = String.format(Locale.US, "Durasi %02d:%02d:%02d", h, m, s),
                                style = TabularTelemetryStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                color = if (isDark) StatusConnectedFgDark else StatusConnectedFgLight
                            )
                        } else if (runtime.status == ConnectionStatus.ERROR && runtime.statusMessage.isNotBlank()) {
                            Text(
                                text = runtime.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (runtime.status == ConnectionStatus.DISCONNECTED) {
                            Text(
                                text = "${active.mode.label} • Tekan Hubungkan untuk memulai",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    StatusBadge(runtime.status)
                }

                Button(
                    onClick = { if (isActive) onStopClick() else onStartClick() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (isActive) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    )
                ) {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isActive) "Putuskan Koneksi" else "Hubungkan",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Zone 2: Tabular Live Telemetry Bar
            TelemetryBar(
                trafficStateFlow = trafficStateFlow,
                isPinging = isPinging,
                onPingClick = onPingClick
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Zone 3: Full-bleed Console Log
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                listOf("ALL" to "Semua", "INFO" to "Info", "ERROR" to "Error").forEach { (key, label) ->
                    val selected = logFilter == key
                    Column(
                        modifier = Modifier
                            .clickable { logFilter = key }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (selected) {
                            Spacer(Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .width(22.dp)
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }
            Row {
                IconButton(
                    onClick = {
                        val debugText = TunnelState.readDebugLog()
                        val text = debugText.ifBlank {
                            runtime.logs.joinToString("\n") { "[${it.timestamp}] ${it.message}" }
                        }
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("vexotun_debug_logs", text))
                        Toast.makeText(context, "Log debug disalin", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = "Salin Log Debug", modifier = Modifier.size(17.dp))
                }
                IconButton(
                    onClick = {
                        val text = visibleLogs.joinToString("\n") { "[${it.timestamp}] ${it.message}" }
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("vexotun_logs", text))
                        Toast.makeText(context, "Log disalin", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Salin Log", modifier = Modifier.size(17.dp))
                }
                IconButton(
                    onClick = onClearLogs,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Bersihkan Log", modifier = Modifier.size(17.dp))
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(LogConsoleBg)
        ) {
            if (visibleLogs.isEmpty()) {
                Text(
                    text = if (logFilter == "ALL") "Belum ada log." else "Tidak ada log untuk filter ini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LogInfoFg.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(visibleLogs, key = { _, entry -> entry.id }) { _, entry ->
                        val color = when (entry.level) {
                            "ERROR" -> LogErrorFg
                            "WARN" -> LogWarnFg
                            else -> LogInfoFg
                        }
                        Text(
                            text = "[${entry.timestamp}] ${entry.message}",
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryBar(
    trafficStateFlow: StateFlow<TrafficStatsState>,
    isPinging: Boolean,
    onPingClick: () -> Unit
) {
    val traffic by trafficStateFlow.collectAsStateWithLifecycle()
    val isDark = LocalIsDarkTheme.current
    val ping = traffic.pingMs
    val pingColor = when {
        ping == null -> MaterialTheme.colorScheme.onSurface
        ping < 140 -> if (isDark) StatusConnectedFgDark else StatusConnectedFgLight
        ping < 280 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                "UNDUH",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatSpeed(traffic.speedDownBps),
                style = TabularTelemetryStyle,
                maxLines = 1
            )
            Text(
                "Total ${formatBytes(traffic.bytesDown)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(38.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                "UNGGAH",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatSpeed(traffic.speedUpBps),
                style = TabularTelemetryStyle,
                maxLines = 1
            )
            Text(
                "Total ${formatBytes(traffic.bytesUp)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(38.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Row(
            modifier = Modifier
                .weight(0.95f)
                .clickable(enabled = !isPinging, onClick = onPingClick)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "PING",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isPinging) "..." else ping?.let { "$it ms" } ?: "--",
                    style = TabularTelemetryStyle,
                    color = pingColor,
                    maxLines = 1
                )
                Text(
                    text = if (isPinging) "Mengukur" else "Ketuk uji",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Cek Ping",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
