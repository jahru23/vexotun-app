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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.vexotun.app.model.LinkExporter
import com.vexotun.app.model.SlowDnsMode
import com.vexotun.app.model.SlowDnsRecordType
import com.vexotun.app.model.SlowDnsTransport
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
import com.vexotun.app.vpn.VpnRuntimeState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppPage(val title: String, val icon: ImageVector) {
    TUNNEL("Tunnel", Icons.Default.PowerSettingsNew),
    CONFIG("Config", Icons.Default.Key),
    APPS("Filter Aplikasi", Icons.Default.Apps),
    SETTINGS("Pengaturan", Icons.Default.Tune)
}

private data class NavEntry(
    val page: AppPage,
    val editingProfileId: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onRequestVpnStart: () -> Unit,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsState()
    val runtime by viewModel.runtimeState.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()
    val isBatchPinging by viewModel.isBatchPinging.collectAsState()
    val pingResults by viewModel.pingResults.collectAsState()

    val navStack = remember { mutableStateListOf(NavEntry(AppPage.TUNNEL)) }
    val currentNav = navStack.lastOrNull() ?: NavEntry(AppPage.TUNNEL)
    val currentPage = currentNav.page
    val editingProfileId = currentNav.editingProfileId

    fun navigateTo(page: AppPage, editId: String? = null) {
        val target = NavEntry(page, editId)
        if (navStack.lastOrNull() != target) {
            navStack.add(target)
        }
    }

    fun navigateBack() {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.lastIndex)
        }
    }

    var showImportDialog by remember { mutableStateOf(false) }
    var showCreateTypeDialog by remember { mutableStateOf(false) }
    var shareLinkText by remember { mutableStateOf<String?>(null) }
    var exportVxtProfile by remember { mutableStateOf<TunnelProfile?>(null) }
    var qrExportData by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingVxtBytes by remember { mutableStateOf<ByteArray?>(null) }
    var lastBackPressMs by remember { mutableLongStateOf(0L) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val vxtExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = pendingVxtBytes
        pendingVxtBytes = null
        if (uri != null && bytes != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.isSuccess
            Toast.makeText(
                context,
                if (ok) "Config .vxt berhasil disimpan" else "Gagal menyimpan file .vxt",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val vxtImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importVxtUri(uri) { result ->
                result.onSuccess { name ->
                    showImportDialog = false
                    Toast.makeText(context, "Config .vxt diimpor: $name", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(context, err.message ?: "Gagal impor .vxt", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    BackHandler(enabled = true) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            navStack.size > 1 -> navigateBack()
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs < 2000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressMs = now
                    Toast.makeText(context, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var showConnectedBanner by remember { mutableStateOf(false) }
    var lastStatus by remember { mutableStateOf(runtime.status) }

    LaunchedEffect(runtime.status) {
        if (runtime.status == ConnectionStatus.CONNECTED && lastStatus != ConnectionStatus.CONNECTED) {
            showConnectedBanner = true
            delay(3000L)
            showConnectedBanner = false
        } else if (runtime.status != ConnectionStatus.CONNECTED) {
            showConnectedBanner = false
        }
        lastStatus = runtime.status
    }

    if (showImportDialog) {
        ImportLinkDialog(
            onDismiss = { showImportDialog = false },
            onPickVxtFile = { vxtImportLauncher.launch(arrayOf("*/*")) },
            onImport = { linkText ->
                val result = viewModel.importFromLinks(linkText)
                if (result.isSuccess) {
                    showImportDialog = false
                }
                result
            }
        )
    }

    if (showCreateTypeDialog) {
        CreateConfigTypeDialog(
            onDismiss = { showCreateTypeDialog = false },
            onSelectMode = { mode ->
                showCreateTypeDialog = false
                val newId = viewModel.createProfile(mode)
                navigateTo(AppPage.CONFIG, newId)
            }
        )
    }

    shareLinkText?.let { link ->
        ShareLinkDialog(
            link = link,
            onShowQr = {
                shareLinkText = null
                qrExportData = "QR Link Config" to link
            },
            onDismiss = { shareLinkText = null }
        )
    }

    exportVxtProfile?.let { profile ->
        ExportVxtDialog(
            profile = profile,
            onDismiss = { exportVxtProfile = null },
            onExportFile = { lockInfo ->
                val bytes = viewModel.exportVxtBytes(profile, lockInfo)
                pendingVxtBytes = bytes
                exportVxtProfile = null
                val safeName = profile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "vexotun" }
                vxtExportLauncher.launch("$safeName.vxt")
            },
            onExportQr = { lockInfo ->
                val encoded = viewModel.exportVxtString(profile, lockInfo)
                exportVxtProfile = null
                qrExportData = "QR ${profile.name}" to encoded
            }
        )
    }

    qrExportData?.let { (title, content) ->
        QrCodeDialog(
            title = title,
            content = content,
            onDismiss = { qrExportData = null }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                ) {
                    Text(
                        "Vexotun",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Xray & SSH Tunnel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))

                listOf(AppPage.APPS, AppPage.SETTINGS).forEach { page ->
                    NavigationDrawerItem(
                        icon = { Icon(page.icon, contentDescription = null) },
                        label = { Text(page.title) },
                        selected = currentPage == page,
                        onClick = {
                            navigateTo(page, null)
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    navigationIcon = {
                        if (navStack.size > 1) {
                            IconButton(onClick = { navigateBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        }
                    },
                    title = {
                        val titleText = when {
                            currentPage == AppPage.TUNNEL -> "Vexotun"
                            currentPage == AppPage.CONFIG && editingProfileId != null -> "Edit Config"
                            else -> currentPage.title
                        }
                        Text(
                            titleText,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        StatusBadge(
                            status = runtime.status,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                )
            },
            bottomBar = {
                if (currentPage == AppPage.TUNNEL || (currentPage == AppPage.CONFIG && editingProfileId == null)) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 0.dp
                    ) {
                        NavigationBarItem(
                            selected = currentPage == AppPage.TUNNEL,
                            onClick = { navigateTo(AppPage.TUNNEL, null) },
                            icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = "Tunnel") },
                            label = { Text("Tunnel") }
                        )
                        NavigationBarItem(
                            selected = currentPage == AppPage.CONFIG,
                            onClick = { navigateTo(AppPage.CONFIG, null) },
                            icon = { Icon(Icons.Default.Key, contentDescription = "Config") },
                            label = { Text("Config") }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentPage) {
                    AppPage.TUNNEL -> TunnelPage(
                        config = config,
                        runtime = runtime,
                        trafficStateFlow = viewModel.trafficState,
                        isPinging = isPinging,
                        onSelectProfile = viewModel::selectProfile,
                        onGoToConfig = { navigateTo(AppPage.CONFIG, null) },
                        onOpenImport = { showImportDialog = true },
                        onStartClick = onRequestVpnStart,
                        onStopClick = viewModel::stopVpn,
                        onPingClick = viewModel::runPingCheck,
                        onClearLogs = viewModel::clearLogs
                    )
                    AppPage.CONFIG -> {
                        val editTarget = editingProfileId?.let { id ->
                            config.profiles.firstOrNull { it.id == id }
                        }
                        if (editTarget != null && !editTarget.isLocked) {
                            ConfigEditorPage(
                                profile = editTarget,
                                onUpdateProfile = { transform ->
                                    viewModel.updateProfile(editTarget.id, transform)
                                },
                                onDone = { navigateBack() }
                            )
                        } else {
                            ConfigListPage(
                                config = config,
                                isBatchPinging = isBatchPinging,
                                pingResults = pingResults,
                                onSelectProfile = viewModel::selectProfile,
                                onEditProfile = { id -> navigateTo(AppPage.CONFIG, id) },
                                onDuplicateProfile = viewModel::duplicateProfile,
                                onShareLink = { profile ->
                                    LinkExporter.exportShareLink(profile)
                                        .onSuccess { shareLinkText = it }
                                        .onFailure { err ->
                                            Toast.makeText(context, err.message ?: "Gagal ekspor link", Toast.LENGTH_SHORT).show()
                                        }
                                },
                                onExportVxt = { profile -> exportVxtProfile = profile },
                                onDeleteProfile = { id ->
                                    navStack.removeAll { it.editingProfileId == id }
                                    viewModel.deleteProfile(id)
                                },
                                onBatchPing = viewModel::runBatchPingCheck,
                                onCreateClick = { showCreateTypeDialog = true },
                                onOpenImport = { showImportDialog = true }
                            )
                        }
                    }
                    AppPage.APPS -> {
                        LaunchedEffect(Unit) { viewModel.ensureAppsLoaded() }
                        SplitTunnelPage(
                            config = config,
                            apps = apps,
                            isVpnConnected = runtime.status == ConnectionStatus.CONNECTED,
                            onSplitModeChange = viewModel::setSplitMode,
                            onTogglePackage = viewModel::toggleSplitPackage,
                            onReconnectVpn = viewModel::reconnectVpn
                        )
                    }
                    AppPage.SETTINGS -> SettingsPage(
                        config = config,
                        onUpdateConfig = viewModel::updateConfig
                    )
                }

                AnimatedVisibility(
                    visible = showConnectedBanner,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Surface(
                        onClick = { showConnectedBanner = false },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0D9488),
                        border = BorderStroke(1.dp, Color(0xFF2DD4BF)),
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "VexoTun Tersambung",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val (bg, fg, label) = when (status) {
        ConnectionStatus.CONNECTED -> Triple(Color(0xFF163835), Color(0xFF4DB6AC), "Terhubung")
        ConnectionStatus.CONNECTING -> Triple(Color(0xFF33260D), Color(0xFFF59E0B), "Menghubungkan")
        ConnectionStatus.ERROR -> Triple(Color(0xFF3B1212), Color(0xFFEF6C6C), "Gagal")
        ConnectionStatus.DISCONNECTED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Siap"
        )
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
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
private fun ModeBadge(mode: TunnelMode, modifier: Modifier = Modifier) {
    val (bg, fg) = if (mode == TunnelMode.XRAY) {
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        Color(0xFF3B2F0B) to Color(0xFFFBBF24)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            mode.label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun CreateConfigTypeDialog(
    onDismiss: () -> Unit,
    onSelectMode: (TunnelMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih Tipe Config") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Pilih jenis engine yang ingin dibuat. Halaman Tunnel akan mendeteksi tipe config secara otomatis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Card(
                    onClick = { onSelectMode(TunnelMode.XRAY) },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Xray", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            ModeBadge(TunnelMode.XRAY)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "VMess, VLESS, Trojan, Shadowsocks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Card(
                    onClick = { onSelectMode(TunnelMode.SSH) },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("SSH", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            ModeBadge(TunnelMode.SSH)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Direct, HTTP Proxy, SSL/TLS, WebSocket",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
private fun ImportLinkDialog(
    onDismiss: () -> Unit,
    onPickVxtFile: () -> Unit,
    onImport: (String) -> Result<Int>
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim()
        if (!contents.isNullOrEmpty()) {
            input = if (input.isBlank()) contents else "$input\n$contents"
            errorMsg = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Config") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tempel link (vmess/vless/trojan/ss), scan QR, atau pilih file .vxt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        errorMsg = null
                    },
                    placeholder = { Text("vmess://...\nvless://...") },
                    minLines = 3,
                    maxLines = 6,
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (clip.isNotBlank()) {
                                input = clip
                                errorMsg = null
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clipboard", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            val options = ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("Arahkan kamera ke QR Config Vexotun")
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                            }
                            qrLauncher.launch(options)
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Scan QR", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = onPickVxtFile,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("File .vxt", fontSize = 12.sp)
                    }
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
private fun ShareLinkDialog(
    link: String,
    onShowQr: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bagikan Link Config") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Text(
                        text = link,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                }
                context.startActivity(Intent.createChooser(sendIntent, "Bagikan Config"))
            }) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Bagikan")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onShowQr) {
                    Text("QR Code")
                }
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("vexotun_link", link))
                    Toast.makeText(context, "Link disalin", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) {
                    Text("Salin")
                }
                TextButton(onClick = onDismiss) {
                    Text("Tutup")
                }
            }
        }
    )
}

@Composable
private fun ExportVxtDialog(
    profile: TunnelProfile,
    onDismiss: () -> Unit,
    onExportFile: (VxtLockInfo) -> Unit,
    onExportQr: (VxtLockInfo) -> Unit
) {
    var lockConfig by remember { mutableStateOf(true) }
    var hideHost by remember { mutableStateOf(true) }
    var hideSni by remember { mutableStateOf(true) }
    var hideAuth by remember { mutableStateOf(true) }
    var hidePayload by remember { mutableStateOf(profile.mode == TunnelMode.SSH) }
    var author by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var expiryDays by remember { mutableStateOf(0) } // 0 = unlimited

    fun buildLockInfo(): VxtLockInfo {
        val expiresAt = if (expiryDays > 0) {
            System.currentTimeMillis() + expiryDays * 24L * 60L * 60L * 1000L
        } else 0L
        return VxtLockInfo(
            locked = lockConfig,
            hideHost = lockConfig && hideHost,
            hideSni = lockConfig && hideSni,
            hideAuth = lockConfig && hideAuth,
            hidePayload = lockConfig && hidePayload,
            author = author.trim(),
            note = note.trim(),
            expiresAtMs = expiresAt
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Config .vxt") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Config: ${profile.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Kunci Config (Anti-Edit/Intip)", fontWeight = FontWeight.Medium)
                    Switch(
                        checked = lockConfig,
                        onCheckedChange = {
                            lockConfig = it
                            if (!it) {
                                hideHost = false
                                hideSni = false
                                hideAuth = false
                                hidePayload = false
                            }
                        }
                    )
                }
                if (lockConfig) {
                    VxtCheckRow("Sembunyikan Host / IP", hideHost) { hideHost = it }
                    VxtCheckRow("Sembunyikan Bug / SNI", hideSni) { hideSni = it }
                    VxtCheckRow("Sembunyikan Akun (UUID/Pass)", hideAuth) { hideAuth = it }
                    if (profile.mode == TunnelMode.SSH) {
                        VxtCheckRow("Sembunyikan Payload SSH", hidePayload) { hidePayload = it }
                    }
                }
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it.replace("\n", "") },
                    label = { Text("Pembuat (Opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan (Opsional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                CompactDropdown(
                    label = "Masa Aktif Config",
                    selectedLabel = when (expiryDays) {
                        3 -> "3 Hari"
                        7 -> "7 Hari"
                        30 -> "30 Hari"
                        else -> "Tanpa Batas"
                    },
                    options = listOf(0, 3, 7, 30),
                    optionLabel = { d -> if (d == 0) "Tanpa Batas" else "$d Hari" },
                    onSelect = { expiryDays = it }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onExportFile(buildLockInfo()) }) {
                Text("Simpan .vxt")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onExportQr(buildLockInfo()) }) {
                    Text("Buat QR")
                }
                TextButton(onClick = onDismiss) {
                    Text("Batal")
                }
            }
        }
    )
}

@Composable
private fun QrCodeDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val qrBitmap = remember(content) { generateQrBitmap(content, 640) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(240.dp)
                                .padding(12.dp)
                        )
                    }
                    Text(
                        "Pindai QR ini dari menu Import di Vexotun lain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text("Gagal membuat QR Code.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (qrBitmap != null) {
                Button(onClick = {
                    val saved = saveBitmapToGallery(context, qrBitmap, "vexotun_qr_${System.currentTimeMillis()}.png")
                    Toast.makeText(
                        context,
                        if (saved) "QR disimpan ke Galeri (Pictures/Vexotun)" else "Gagal menyimpan QR",
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text("Simpan ke Galeri")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        }
    )
}

private fun generateQrBitmap(text: String, sizePx: Int): Bitmap? {
    return runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean {
    return runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Vexotun")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrDefault(false)
}

@Composable
private fun VxtCheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunnelPage(
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
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        if (active == null) {
            item(key = "empty_config") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(38.dp)
                        )
                        Text(
                            "Belum Ada Config",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Buat config Xray atau SSH terlebih dahulu di halaman Config, atau import link untuk mulai menghubungkan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(2.dp))
                        Button(
                            onClick = onGoToConfig,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Buat Config")
                        }
                        OutlinedButton(
                            onClick = onOpenImport,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import Config")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        } else {
            item(key = "config_selector") {
                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded && !isActive,
                    onExpandedChange = { if (!isActive) expanded = !expanded }
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Config Aktif",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    ModeBadge(active.mode)
                                    if (active.isLocked) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = "Terkunci",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    active.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
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
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            meta,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (!isActive) {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            }
                        }
                    }

                    ExposedDropdownMenu(
                        expanded = expanded && !isActive,
                        onDismissRequest = { expanded = false }
                    ) {
                        config.profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                profile.name,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                profile.summary(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        ModeBadge(profile.mode)
                                    }
                                },
                                onClick = {
                                    onSelectProfile(profile.id)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            item(key = "connection_panel") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (runtime.status) {
                                        ConnectionStatus.CONNECTED -> "Tunnel Terhubung"
                                        ConnectionStatus.CONNECTING -> "Menghubungkan..."
                                        ConnectionStatus.ERROR -> "Koneksi Gagal"
                                        ConnectionStatus.DISCONNECTED -> "Siap Dihubungkan"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Engine: ${active.mode.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusBadge(runtime.status)
                        }

                        if (runtime.statusMessage.isNotBlank() && runtime.status == ConnectionStatus.ERROR) {
                            Text(
                                text = runtime.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Button(
                            onClick = { if (isActive) onStopClick() else onStartClick() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isActive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
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
                                if (isActive) "Putuskan Koneksi" else "Hubungkan",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        item(key = "ping_row") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onPingClick,
                    enabled = !isPinging && active != null,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.NetworkPing, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isPinging) "Mengukur..." else "Cek Ping")
                }
                PingStatusChip(
                    trafficStateFlow = trafficStateFlow,
                    modifier = Modifier.height(46.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        item(key = "log_header") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Log",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = {
                                    val text = runtime.logs.joinToString("\n") {
                                        "[${it.timestamp}] ${it.message}"
                                    }
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("vexotun_logs", text))
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Salin Log",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = onClearLogs,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Bersihkan Log",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        if (runtime.logs.isEmpty()) {
            item(key = "log_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                        .background(Color(0xFF0B0D0F))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Belum ada log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            itemsIndexed(
                items = runtime.logs,
                key = { _, entry -> entry.id }
            ) { index, entry ->
                val isLast = index == runtime.logs.lastIndex
                val color = when (entry.level) {
                    "ERROR" -> Color(0xFFEF6C6C)
                    "WARN" -> Color(0xFFF59E0B)
                    else -> Color(0xFF80CBC4)
                }
                val shape = if (isLast) {
                    RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                } else {
                    RoundedCornerShape(0.dp)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Color(0xFF0B0D0F))
                        .padding(
                            start = 12.dp,
                            end = 12.dp,
                            top = if (index == 0) 8.dp else 2.dp,
                            bottom = if (isLast) 10.dp else 2.dp
                        )
                ) {
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

@Composable
private fun PingStatusChip(
    trafficStateFlow: StateFlow<TrafficStatsState>,
    modifier: Modifier = Modifier
) {
    val traffic by trafficStateFlow.collectAsState()
    val ping = traffic.pingMs
    val accent = when {
        ping == null -> MaterialTheme.colorScheme.onSurfaceVariant
        ping < 120 -> Color(0xFF4DB6AC)
        ping < 250 -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Ping",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = ping?.let { "$it ms" } ?: "--",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ConfigListPage(
    config: VexotunConfig,
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onCreateClick,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Buat", maxLines = 1, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onOpenImport,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
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
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.NetworkPing, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isBatchPinging) "..." else "Tes Ping", maxLines = 1, fontSize = 13.sp)
            }
        }

        if (config.profiles.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
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
                        "Belum Ada Config",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Tekan \"Buat\" untuk membuat config Xray / SSH baru, atau \"Import\" untuk link, QR, dan file .vxt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(config.profiles, key = { it.id }) { profile ->
                    val isSelected = profile.id == config.activeProfile()?.id
                    val latency = pingResults[profile.id]
                    var menuExpanded by remember { mutableStateOf(false) }

                    Surface(
                        onClick = { onSelectProfile(profile.id) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ModeBadge(profile.mode)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        profile.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (profile.isLocked) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = "Terkunci",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    if (isSelected) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Aktif",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
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
                                    latency < 120 -> Color(0xFF4DB6AC)
                                    latency < 250 -> Color(0xFFF59E0B)
                                    else -> MaterialTheme.colorScheme.error
                                }
                                Text(
                                    text = if (latency >= 0) "${latency}ms" else "Timeout",
                                    color = pingColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                            }
                            Box {
                                IconButton(
                                    onClick = { menuExpanded = true },
                                    modifier = Modifier.size(36.dp)
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
                                            text = { Text("Edit") },
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
                                        text = { Text("Hapus", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menuExpanded = false
                                            onDeleteProfile(profile.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigEditorPage(
    profile: TunnelProfile,
    onUpdateProfile: ((TunnelProfile) -> TunnelProfile) -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = profile.name,
            onValueChange = { v -> onUpdateProfile { it.copy(name = v) } },
            label = { Text("Nama Config") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TunnelMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = profile.mode == mode,
                    onClick = { onUpdateProfile { it.copy(mode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index, TunnelMode.entries.size)
                ) {
                    Text(mode.label)
                }
            }
        }

        if (profile.mode == TunnelMode.XRAY) {
            XrayProfileEditor(profile = profile, onUpdateProfile = onUpdateProfile)
        } else {
            SshProfileEditor(profile = profile, onUpdateProfile = onUpdateProfile)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> CompactDropdown(
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
private fun XrayProfileEditor(
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

            if (x.tls) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Allow Insecure",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = x.allowInsecure,
                        onCheckedChange = { checked ->
                            onUpdateProfile { it.copy(xray = it.xray.copy(allowInsecure = checked)) }
                        }
                    )
                }
            }

            if (x.flow.isBlank() && x.network != XrayTransport.GRPC) {
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
            OutlinedTextField(
                value = x.proxyPort.toString(),
                onValueChange = { v ->
                    val p = v.filter(Char::isDigit).toIntOrNull() ?: 8080
                    onUpdateProfile { it.copy(xray = it.xray.copy(proxyPort = p)) }
                },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
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

@Composable
private fun SshProfileEditor(
    profile: TunnelProfile,
    onUpdateProfile: ((TunnelProfile) -> TunnelProfile) -> Unit
) {
    val context = LocalContext.current
    val s = profile.ssh
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    OutlinedButton(
        onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            val parsed = parseSshAccountPaste(clip)
            if (parsed != null) {
                onUpdateProfile { p ->
                    p.copy(
                        ssh = p.ssh.copy(
                            host = parsed.host.ifBlank { p.ssh.host },
                            port = if (parsed.port > 0) parsed.port else p.ssh.port,
                            username = parsed.username.ifBlank { p.ssh.username },
                            password = parsed.password.ifBlank { p.ssh.password },
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
        onSelect = { t -> onUpdateProfile { it.copy(ssh = it.ssh.copy(transport = t)) } }
    )

    if (s.transport != SshTransport.SLOWDNS) {
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
    } else {
        Text(
            "Mode SlowDNS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SlowDnsMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = s.dnsMode == mode,
                    onClick = { onUpdateProfile { it.copy(ssh = it.ssh.copy(dnsMode = mode)) } },
                    shape = SegmentedButtonDefaults.itemShape(index, SlowDnsMode.entries.size)
                ) {
                    Text(mode.label, maxLines = 1)
                }
            }
        }

        Text(
            "Transport DNS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SlowDnsTransport.entries.forEachIndexed { index, tr ->
                SegmentedButton(
                    selected = s.dnsTransport == tr,
                    onClick = {
                        val defaultResolver = when (tr) {
                            SlowDnsTransport.UDP -> "8.8.8.8"
                            SlowDnsTransport.DOH -> "https://dns.google/dns-query"
                            SlowDnsTransport.DOT -> "dns.google:853"
                        }
                        onUpdateProfile {
                            val current = it.ssh.dnsServer.trim()
                            val shouldReplace = current.isBlank() ||
                                current == "8.8.8.8" ||
                                current.startsWith("https://") ||
                                current.endsWith(":853")
                            it.copy(
                                ssh = it.ssh.copy(
                                    dnsTransport = tr,
                                    dnsServer = if (shouldReplace) defaultResolver else current
                                )
                            )
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, SlowDnsTransport.entries.size)
                ) {
                    Text(tr.label, maxLines = 1)
                }
            }
        }

        if (s.dnsMode == SlowDnsMode.VAYDNS) {
            CompactDropdown(
                label = "Tipe Record",
                selectedLabel = s.dnsType.label,
                options = SlowDnsRecordType.entries,
                optionLabel = { it.label },
                onSelect = { dt -> onUpdateProfile { it.copy(ssh = it.ssh.copy(dnsType = dt)) } }
            )
        } else {
            Text(
                "Mode dnstt: otomatis TXT (idle-timeout 2m, keepalive 10s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = s.nameserver,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(nameserver = v.replace("\n", ""))) } },
            label = { Text("Nameserver (NS)") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.publicKey,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(publicKey = v.replace("\n", ""))) } },
            label = { Text("Public Key") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.dnsServer,
            onValueChange = { v -> onUpdateProfile { it.copy(ssh = it.ssh.copy(dnsServer = v.replace("\n", ""))) } },
            label = {
                Text(
                    when (s.dnsTransport) {
                        SlowDnsTransport.UDP -> "DNS Resolver (mis. 8.8.8.8)"
                        SlowDnsTransport.DOH -> "URL DoH (mis. https://dns.google/dns-query)"
                        SlowDnsTransport.DOT -> "Host DoT (mis. dns.google:853)"
                    }
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
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
private fun SplitTunnelPage(
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
private fun SettingsPage(
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
                OutlinedTextField(
                    value = config.mtu.toString(),
                    onValueChange = { v ->
                        val m = v.filter(Char::isDigit).toIntOrNull() ?: 1420
                        onUpdateConfig { it.copy(mtu = m) }
                    },
                    label = { Text("MTU") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun findLocalLanIp(): String {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: "192.168.43.1"
}

@Composable
private fun SettingsSwitchRow(
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

private fun parseSshAccountPaste(raw: String): SshProfile? {
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
        SshProfile(
            host = host,
            port = if (port > 0) port else 443,
            username = user,
            password = pass,
            nameserver = ns,
            publicKey = pubkey
        )
    } else {
        null
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
