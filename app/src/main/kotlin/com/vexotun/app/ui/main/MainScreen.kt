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
import com.vexotun.app.model.VxtPinRequiredException
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

private enum class AppPage(val title: String, val icon: ImageVector) {
    TUNNEL("Tunnel", Icons.Default.PowerSettingsNew),
    CONFIG("Config", Icons.Default.Key),
    TOOLS("Tools Jaringan", Icons.Default.Build),
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
    val config by viewModel.config.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeState.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val isPinging by viewModel.isPinging.collectAsStateWithLifecycle()
    val isBatchPinging by viewModel.isBatchPinging.collectAsStateWithLifecycle()
    val pingResults by viewModel.pingResults.collectAsStateWithLifecycle()

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
    var pendingPinUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPinText by remember { mutableStateOf<String?>(null) }
    var lastBackPressMs by remember { mutableLongStateOf(0L) }
    val isTunnelBusy = runtime.status == ConnectionStatus.CONNECTED || runtime.status == ConnectionStatus.CONNECTING

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
                    if (err is VxtPinRequiredException) {
                        pendingPinUri = uri
                    } else {
                        Toast.makeText(context, err.message ?: "Gagal impor .vxt", Toast.LENGTH_LONG).show()
                    }
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
                } else if (result.exceptionOrNull() is VxtPinRequiredException) {
                    showImportDialog = false
                    pendingPinText = linkText
                }
                result
            }
        )
    }

    if (pendingPinUri != null || pendingPinText != null) {
        VxtPinPromptDialog(
            onDismiss = {
                pendingPinUri = null
                pendingPinText = null
            },
            onConfirm = { enteredPin ->
                val uri = pendingPinUri
                val text = pendingPinText
                if (uri != null) {
                    viewModel.importVxtUri(uri, enteredPin) { result ->
                        result.onSuccess { name ->
                            pendingPinUri = null
                            showImportDialog = false
                            Toast.makeText(context, "Config .vxt diimpor: $name", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            Toast.makeText(context, err.message ?: "PIN salah", Toast.LENGTH_LONG).show()
                        }
                    }
                } else if (text != null) {
                    viewModel.importFromLinks(text, enteredPin).onSuccess {
                        pendingPinText = null
                        Toast.makeText(context, "Config .vxt berhasil diimpor", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        Toast.makeText(context, err.message ?: "PIN salah", Toast.LENGTH_LONG).show()
                    }
                }
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
            onExportFile = { lockInfo, pin ->
                val bytes = viewModel.exportVxtBytes(profile, lockInfo, pin)
                pendingVxtBytes = bytes
                exportVxtProfile = null
                val safeName = profile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "vexotun" }
                vxtExportLauncher.launch("$safeName.vxt")
            },
            onExportQr = { lockInfo, pin ->
                val encoded = viewModel.exportVxtString(profile, lockInfo, pin)
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
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF081620),
                        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "V",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp
                            )
                        }
                    }
                    Column {
                        Text(
                            "Vexotun",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Xray & SSH Tunnel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))

                listOf(AppPage.TOOLS, AppPage.APPS, AppPage.SETTINGS).forEach { page ->
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
                            currentPage == AppPage.CONFIG && editingProfileId != null -> {
                                val editMode = config.profiles.firstOrNull { it.id == editingProfileId }?.mode?.label
                                if (editMode != null) "Edit $editMode" else "Edit Config"
                            }
                            else -> currentPage.title
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (currentPage == AppPage.TUNNEL) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF081620),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "V",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                            Text(
                                titleText,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        if (runtime.status == ConnectionStatus.CONNECTED || runtime.status == ConnectionStatus.ERROR) {
                            val dotColor = if (runtime.status == ConnectionStatus.CONNECTED) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                            Box(
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                        }
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
                        onSelectProfile = { id ->
                            if (isTunnelBusy && id != config.activeProfile()?.id) {
                                Toast.makeText(
                                    context,
                                    "Putus VPN terlebih dahulu untuk mengganti profil aktif",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                viewModel.selectProfile(id)
                            }
                        },
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
                                isLockedByVpn = isTunnelBusy && config.activeProfile()?.id == editTarget.id,
                                onUpdateProfile = { transform ->
                                    viewModel.updateProfile(editTarget.id, transform)
                                },
                                onDone = { navigateBack() }
                            )
                        } else {
                            ConfigListPage(
                                config = config,
                                isTunnelBusy = isTunnelBusy,
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
                    AppPage.TOOLS -> ToolsPage(
                        socksPort = config.socksPort,
                        isVpnConnected = runtime.status == ConnectionStatus.CONNECTED
                    )
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
