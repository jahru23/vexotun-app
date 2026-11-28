package com.vexotun.app.ui.main

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.TunnelProfile
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.ui.theme.BadgeSshBgDark
import com.vexotun.app.ui.theme.BadgeSshBgLight
import com.vexotun.app.ui.theme.BadgeSshFgDark
import com.vexotun.app.ui.theme.BadgeSshFgLight
import com.vexotun.app.ui.theme.BadgeXrayBgDark
import com.vexotun.app.ui.theme.BadgeXrayBgLight
import com.vexotun.app.ui.theme.BadgeXrayFgDark
import com.vexotun.app.ui.theme.BadgeXrayFgLight
import com.vexotun.app.ui.theme.LocalIsDarkTheme
import com.vexotun.app.ui.theme.StatusConnectedBgDark
import com.vexotun.app.ui.theme.StatusConnectedBgLight
import com.vexotun.app.ui.theme.StatusConnectedFgDark
import com.vexotun.app.ui.theme.StatusConnectedFgLight
import com.vexotun.app.ui.theme.StatusConnectingBgDark
import com.vexotun.app.ui.theme.StatusConnectingBgLight
import com.vexotun.app.ui.theme.StatusConnectingFgDark
import com.vexotun.app.ui.theme.StatusConnectingFgLight
import com.vexotun.app.ui.theme.StatusErrorBgDark
import com.vexotun.app.ui.theme.StatusErrorBgLight
import com.vexotun.app.ui.theme.StatusErrorFgDark
import com.vexotun.app.ui.theme.StatusErrorFgLight
import com.vexotun.app.ui.theme.TabularTelemetryStyle
import com.vexotun.app.vpn.ConnectionStatus

@Composable
internal fun StatusBadge(status: ConnectionStatus, modifier: Modifier = Modifier) {
    if (status == ConnectionStatus.DISCONNECTED) return
    val isDark = LocalIsDarkTheme.current
    val (bg, fg, label) = when (status) {
        ConnectionStatus.CONNECTED -> Triple(
            if (isDark) StatusConnectedBgDark else StatusConnectedBgLight,
            if (isDark) StatusConnectedFgDark else StatusConnectedFgLight,
            "Terhubung"
        )
        ConnectionStatus.CONNECTING -> Triple(
            if (isDark) StatusConnectingBgDark else StatusConnectingBgLight,
            if (isDark) StatusConnectingFgDark else StatusConnectingFgLight,
            "Menghubungkan"
        )
        ConnectionStatus.ERROR -> Triple(
            if (isDark) StatusErrorBgDark else StatusErrorBgLight,
            if (isDark) StatusErrorFgDark else StatusErrorFgLight,
            "Gagal"
        )
        ConnectionStatus.DISCONNECTED -> return
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
internal fun ModeBadge(mode: TunnelMode, modifier: Modifier = Modifier) {
    val isDark = LocalIsDarkTheme.current
    val (bg, fg) = if (mode == TunnelMode.XRAY) {
        if (isDark) BadgeXrayBgDark to BadgeXrayFgDark else BadgeXrayBgLight to BadgeXrayFgLight
    } else {
        if (isDark) BadgeSshBgDark to BadgeSshFgDark else BadgeSshBgLight to BadgeSshFgLight
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            mode.label,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
internal fun ConfigListPage(
    config: VexotunConfig,
    isTunnelBusy: Boolean,
    isBatchPinging: Boolean,
    pingResults: Map<String, Int>,
    onSelectProfile: (String) -> Unit,
    onEditProfile: (String) -> Unit,
    onDuplicateProfile: (String) -> Unit,
    onShareLink: (TunnelProfile) -> Unit,
    onExportVxt: (TunnelProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onBatchPing: () -> Unit,
    onCreateClick: () -> Unit,
    onOpenImport: () -> Unit
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onCreateClick,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Buat", maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onOpenImport,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import", maxLines = 1, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onBatchPing,
                enabled = !isBatchPinging && config.profiles.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.NetworkPing, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isBatchPinging) "..." else "Uji TCP", maxLines = 1, fontSize = 13.sp)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (config.profiles.isEmpty()) {
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
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Belum Ada Profil",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Tekan \"Buat\" untuk membuat config Xray/SSH, atau \"Import\" untuk link, QR, dan file .vxt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(config.profiles, key = { _, p -> p.id }) { _, profile ->
                    val isSelected = profile.id == config.activeProfile()?.id
                    val isBusySelected = isSelected && isTunnelBusy
                    val latency = pingResults[profile.id]
                    var menuExpanded by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.background
                                }
                            )
                            .clickable {
                                if (isTunnelBusy && !isSelected) {
                                    Toast.makeText(
                                        context,
                                        "Putus VPN terlebih dahulu untuk mengganti profil aktif",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    onSelectProfile(profile.id)
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(58.dp)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.background.copy(alpha = 0f)
                                )
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 13.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ModeBadge(profile.mode)
                                Text(
                                    profile.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (profile.isLocked) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = "Terkunci",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                profile.summary(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (latency != null) {
                            val pingColor = when {
                                latency < 0 -> MaterialTheme.colorScheme.error
                                latency < 140 -> if (isDark) StatusConnectedFgDark else StatusConnectedFgLight
                                latency < 280 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            }
                            Text(
                                text = if (latency >= 0) "${latency} ms" else "Timeout",
                                color = pingColor,
                                style = TabularTelemetryStyle.copy(fontSize = 12.sp),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }

                        Box(modifier = Modifier.padding(end = 6.dp)) {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Opsi Config",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                if (!profile.isLocked) {
                                    DropdownMenuItem(
                                        text = { Text(if (isBusySelected) "Edit (Putus VPN dulu)" else "Edit") },
                                        enabled = !isBusySelected,
                                        onClick = {
                                            menuExpanded = false
                                            onEditProfile(profile.id)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Duplikat") },
                                        onClick = {
                                            menuExpanded = false
                                            onDuplicateProfile(profile.id)
                                        }
                                    )
                                    if (profile.mode == TunnelMode.XRAY) {
                                        DropdownMenuItem(
                                            text = { Text("Share Link") },
                                            onClick = {
                                                menuExpanded = false
                                                onShareLink(profile)
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Export .vxt") },
                                        onClick = {
                                            menuExpanded = false
                                            onExportVxt(profile)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isBusySelected) "Hapus (Putus VPN dulu)" else "Hapus",
                                            color = if (isBusySelected) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            }
                                        )
                                    },
                                    enabled = !isBusySelected,
                                    onClick = {
                                        menuExpanded = false
                                        onDeleteProfile(profile.id)
                                    }
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
