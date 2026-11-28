package com.vexotun.app.ui.main

import android.app.Activity
import android.net.Uri
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vexotun.app.model.LinkExporter
import com.vexotun.app.model.TunnelProfile
import com.vexotun.app.model.VxtPinRequiredException
import com.vexotun.app.ui.theme.LocalIsDarkTheme
import com.vexotun.app.ui.theme.StatusConnectedBgDark
import com.vexotun.app.ui.theme.StatusConnectedFgDark
import com.vexotun.app.vpn.ConnectionStatus
import kotlinx.coroutines.delay

private enum class AppPage(val title: String, val icon: ImageVector) {
    TUNNEL("Tunnel", Icons.Default.PowerSettingsNew),
    CONFIG("Config", Icons.Default.Key),
    TOOLS("Tools", Icons.Default.Build),
    SETTINGS("Setelan", Icons.Default.Tune),
    APPS("Filter Aplikasi", Icons.Default.Tune)
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
    val isDark = LocalIsDarkTheme.current
    val config by viewModel.config.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeState.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val isPinging by viewModel.isPinging.collectAsStateWithLifecycle()
    val isBatchPinging by viewModel.isBatchPinging.collectAsStateWithLifecycle()
    val pingResults by viewModel.pingResults.collectAsStateWithLifecycle()
    val dnsScanState by viewModel.dnsScanState.collectAsStateWithLifecycle()

    val navStack = remember { mutableStateListOf(NavEntry(AppPage.TUNNEL)) }
    val currentNav = navStack.lastOrNull() ?: NavEntry(AppPage.TUNNEL)
    val currentPage = currentNav.page
    val editingProfileId = currentNav.editingProfileId
    val isTopLevel = currentPage != AppPage.APPS && editingProfileId == null

    fun navigateTopTab(page: AppPage) {
        navStack.clear()
        navStack.add(NavEntry(page, null))
    }

    fun navigateSubPage(page: AppPage, editId: String? = null) {
        val target = NavEntry(page, editId)
        if (navStack.lastOrNull() != target) {
            navStack.add(target)
        }
    }

    fun navigateBack() {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.lastIndex)
        } else if (!isTopLevel) {
            navStack.clear()
            navStack.add(NavEntry(AppPage.TUNNEL))
        }
    }

    var showImportDialog by remember { mutableStateOf(false) }
    var showCreateTypeDialog by remember { mutableStateOf(false) }
    var shareLinkText by remember { mutableStateOf<String?>(null) }
    var exportVxtProfile by remember { mutableStateOf<TunnelProfile?>(null) }
    var qrExportData by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingVxtBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingPinUriStr by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingPinUri = pendingPinUriStr?.let(Uri::parse)
    var pendingPinText by remember { mutableStateOf<String?>(null) }
    val externalPinUri by viewModel.pendingExternalPinUri.collectAsStateWithLifecycle()
    LaunchedEffect(externalPinUri) {
        val ext = externalPinUri
        if (ext != null) {
            pendingPinUriStr = ext.toString()
            viewModel.clearPendingExternalPinUri()
        }
    }
    var lastBackPressMs by remember { mutableLongStateOf(0L) }
    val isTunnelBusy = runtime.status == ConnectionStatus.CONNECTED || runtime.status == ConnectionStatus.CONNECTING

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
                        showImportDialog = false
                        pendingPinUriStr = uri.toString()
                    } else {
                        Toast.makeText(context, err.message ?: "Gagal impor .vxt", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    BackHandler(enabled = true) {
        when {
            navStack.size > 1 -> navigateBack()
            currentPage != AppPage.TUNNEL -> navigateTopTab(AppPage.TUNNEL)
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
            delay(2500L)
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
                pendingPinUriStr = null
                pendingPinText = null
            },
            onConfirm = { enteredPin ->
                val uri = pendingPinUri
                val text = pendingPinText
                if (uri != null) {
                    viewModel.importVxtUri(uri, enteredPin) { result ->
                        result.onSuccess { name ->
                            pendingPinUriStr = null
                            showImportDialog = false
                            Toast.makeText(context, "Config .vxt diimpor: $name", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            Toast.makeText(context, err.message ?: "PIN salah", Toast.LENGTH_LONG).show()
                        }
                    }
                } else if (text != null) {
                    viewModel.importFromLinks(text, enteredPin).onSuccess {
                        pendingPinText = null
                        showImportDialog = false
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
                navigateSubPage(AppPage.CONFIG, newId)
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

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    if (!isTopLevel) {
                        IconButton(onClick = { navigateBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
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
                                color = Color(0xFF111214),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                modifier = Modifier.size(24.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "V",
                                        color = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFFF59E0B),
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
                            if (isDark) StatusConnectedFgDark else StatusConnectedFgLight
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
            if (isTopLevel) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 0.dp
                ) {
                    listOf(AppPage.TUNNEL, AppPage.CONFIG, AppPage.TOOLS, AppPage.SETTINGS).forEach { tab ->
                        NavigationBarItem(
                            selected = currentPage == tab,
                            onClick = { navigateTopTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
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
                    onGoToConfig = { navigateTopTab(AppPage.CONFIG) },
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
                            dnsScanState = dnsScanState,
                            onScanDns = viewModel::scanSlowDns,
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
                            onEditProfile = { id -> navigateSubPage(AppPage.CONFIG, id) },
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

                AppPage.SETTINGS -> SettingsPage(
                    config = config,
                    onOpenAppsFilter = { navigateSubPage(AppPage.APPS) },
                    onUpdateConfig = viewModel::updateConfig
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
                    shape = RoundedCornerShape(8.dp),
                    color = StatusConnectedBgDark,
                    border = BorderStroke(1.dp, StatusConnectedFgDark),
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
                            tint = StatusConnectedFgDark,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Tunnel Tersambung",
                            color = StatusConnectedFgDark,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
