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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.vexotun.app.model.SplitTunnelMode
import com.vexotun.app.model.SshTransport
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.TunnelProfile
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.model.XrayProtocol
import com.vexotun.app.model.XrayTransport
import com.vexotun.app.vpn.ConnectionStatus
import com.vexotun.app.vpn.VpnRuntimeState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.filled.CheckCircle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class AppPage(val title: String, val icon: ImageVector) {
    TUNNEL("Tunnel", Icons.Default.PowerSettingsNew),
    CONFIG("Config", Icons.Default.Key),
    APPS("Filter Aplikasi", Icons.Default.Apps),
    SETTINGS("Pengaturan", Icons.Default.Tune)
}

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

    var currentPage by rememberSaveable { mutableStateOf(AppPage.TUNNEL) }
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showCreateTypeDialog by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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
            onImport = { linkText ->
                val result = viewModel.importFromLink(linkText)
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
                editingProfileId = newId
                currentPage = AppPage.CONFIG
            }
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
                            editingProfileId = null
                            currentPage = page
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
                        when {
                            currentPage == AppPage.CONFIG && editingProfileId != null -> {
                                IconButton(onClick = { editingProfileId = null }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                                }
                            }
                            currentPage == AppPage.APPS || currentPage == AppPage.SETTINGS -> {
                                IconButton(onClick = { currentPage = AppPage.TUNNEL }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                                }
                            }
                            else -> {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }
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
                            onClick = { currentPage = AppPage.TUNNEL },
                            icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = "Tunnel") },
                            label = { Text("Tunnel") }
                        )
                        NavigationBarItem(
                            selected = currentPage == AppPage.CONFIG,
                            onClick = { currentPage = AppPage.CONFIG },
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
                        isPinging = isPinging,
                        onSelectProfile = viewModel::selectProfile,
                        onGoToConfig = {
                            editingProfileId = null
                            currentPage = AppPage.CONFIG
                        },
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
                        if (editTarget != null) {
                            ConfigEditorPage(
                                profile = editTarget,
                                onUpdateProfile = { transform ->
                                    viewModel.updateProfile(editTarget.id, transform)
                                },
                                onDone = { editingProfileId = null }
                            )
                        } else {
                            ConfigListPage(
                                config = config,
                                onSelectProfile = viewModel::selectProfile,
                                onEditProfile = { id -> editingProfileId = id },
                                onDeleteProfile = { id ->
                                    if (editingProfileId == id) editingProfileId = null
                                    viewModel.deleteProfile(id)
                                },
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
                            onSplitModeChange = viewModel::setSplitMode,
                            onTogglePackage = viewModel::toggleSplitPackage
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
        ConnectionStatus.CONNECTED -> Triple(Color(0xFF064E3B), Color(0xFF34D399), "Terhubung")
        ConnectionStatus.CONNECTING -> Triple(Color(0xFF78350F), Color(0xFFFBBF24), "Menghubungkan")
        ConnectionStatus.ERROR -> Triple(Color(0xFF7F1D1D), Color(0xFFF87171), "Gagal")
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
    onImport: (String) -> Result<TunnelProfile>
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Link Config") },
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
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunnelPage(
    config: VexotunConfig,
    runtime: VpnRuntimeState,
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
    val logListState = rememberLazyListState()

    LaunchedEffect(runtime.logs.size) {
        if (runtime.logs.isNotEmpty()) {
            logListState.animateScrollToItem(runtime.logs.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (active == null) {
            // Empty state: user must create or import config first
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                        modifier = Modifier.size(40.dp)
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
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onGoToConfig,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Buat Config")
                    }
                    OutlinedButton(
                        onClick = onOpenImport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import Link")
                    }
                }
            }
        } else {
            // Config Dropdown Selector
            var expanded by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = expanded && !isActive,
                onExpandedChange = { if (!isActive) expanded = !expanded }
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
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

            // Connection Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(
                    1.dp,
                    if (runtime.status == ConnectionStatus.CONNECTED) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                ),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                                text = "Engine otomatis: ${active.mode.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ModeBadge(active.mode)
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
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isActive) {
                                Color(0xFFDC2626)
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
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
        }

        // Traffic & Ping Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard("Upload", formatBytes(runtime.bytesUp), Modifier.weight(1f))
            StatCard("Download", formatBytes(runtime.bytesDown), Modifier.weight(1f))
            StatCard("Ping", runtime.pingMs?.let { "$it ms" } ?: "--", Modifier.weight(1f))
        }

        OutlinedButton(
            onClick = onPingClick,
            enabled = !isPinging && active != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.NetworkPing, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isPinging) "Mengukur Latency..." else "Cek Ping")
        }

        // Inline Live Log Console
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color(0xFF070B12))
                ) {
                    if (runtime.logs.isEmpty()) {
                        Text(
                            "Belum ada log.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(12.dp)
                        )
                    } else {
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(runtime.logs) { entry ->
                                val color = when (entry.level) {
                                    "ERROR" -> Color(0xFFF87171)
                                    "WARN" -> Color(0xFFFBBF24)
                                    else -> Color(0xFF34D399)
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
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConfigListPage(
    config: VexotunConfig,
    onSelectProfile: (String) -> Unit,
    onEditProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
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
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onCreateClick,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Buat Config", maxLines = 1)
            }
            OutlinedButton(
                onClick = onOpenImport,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import Link", maxLines = 1)
            }
        }

        if (config.profiles.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                        "Tekan \"Buat Config\" untuk membuat config Xray / SSH baru, atau \"Import Link\" untuk menempel link.",
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
                    Card(
                        onClick = { onSelectProfile(profile.id) },
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
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
                            IconButton(
                                onClick = { onEditProfile(profile.id) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit Config",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onDeleteProfile(profile.id) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Hapus Config",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
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

    Card(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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

    CompactDropdown(
        label = "Transport",
        selectedLabel = s.transport.label,
        options = SshTransport.entries,
        optionLabel = { it.label },
        onSelect = { t -> onUpdateProfile { it.copy(ssh = it.ssh.copy(transport = t)) } }
    )

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

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in config.splitPackages
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onTogglePackage(app.packageName) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { onTogglePackage(app.packageName) })
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mode Gelap", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tampilan gelap untuk kenyamanan mata",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.darkTheme,
                    onCheckedChange = { dark -> onUpdateConfig { it.copy(darkTheme = dark) } }
                )
            }
        }

        Card(
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Jaringan", fontWeight = FontWeight.SemiBold)
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
