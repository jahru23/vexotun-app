package com.vexotun.app.ui.main

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.UUID
import com.vexotun.app.data.ConfigStore
import com.vexotun.app.model.LinkParser
import com.vexotun.app.model.SplitTunnelMode
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.TunnelProfile
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.model.VxtCodec
import com.vexotun.app.model.VxtLockInfo
import com.vexotun.app.vpn.ConnectionStatus
import com.vexotun.app.vpn.TunnelState
import com.vexotun.app.vpn.VexotunVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isLauncher: Boolean
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ConfigStore(application)

    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<VexotunConfig> = _config.asStateFlow()

    val runtimeState = TunnelState.state
    val trafficState = TunnelState.trafficState

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _isBatchPinging = MutableStateFlow(false)
    val isBatchPinging: StateFlow<Boolean> = _isBatchPinging.asStateFlow()

    private val _pingResults = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pingResults: StateFlow<Map<String, Int>> = _pingResults.asStateFlow()

    init {
        logDeviceAndNetworkInfo()
    }

    fun ensureAppsLoaded() {
        if (_apps.value.isEmpty()) {
            loadInstalledApps()
        }
    }

    private fun logDeviceAndNetworkInfo() {
        val ctx = getApplication<Application>()
        TunnelState.init(ctx)
        val (versionName, versionCode) = runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val name = info.versionName ?: "1.0.0"
            val code = PackageInfoCompat.getLongVersionCode(info)
            name to code
        }.getOrElse { "1.0.0" to 1L }

        val deviceLine = "${Build.MANUFACTURER.uppercase(Locale.ROOT)} ${Build.MODEL}".trim()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val osLine = "Android ${Build.VERSION.RELEASE} API-${Build.VERSION.SDK_INT} / $abi"

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transportLabel = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Seluler"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Jaringan"
        }
        val carrier = detectActiveDataCarrier(ctx)
        val networkLine = if (carrier.isNotEmpty() && transportLabel == "Seluler") {
            "$carrier • $transportLabel"
        } else {
            transportLabel
        }

        val localIp = activeNetwork?.let { net ->
            cm.getLinkProperties(net)?.linkAddresses
                ?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } ?: "-"

        val netSummary = if (localIp != "-") "$networkLine • $localIp" else networkLine
        TunnelState.ensureInitialLogs(
            messages = listOf(
                "Perangkat: $deviceLine • Android ${Build.VERSION.RELEASE} ($abi)",
                "Jaringan: $netSummary"
            ),
            debugMessages = listOf(
                "App: Vexotun $versionName ($versionCode)",
                "Device: $deviceLine | $osLine",
                "Network: $networkLine | Local IP: $localIp"
            )
        )
    }

    private fun detectActiveDataCarrier(ctx: Context): String {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dataSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SubscriptionManager.getActiveDataSubscriptionId()
            } else {
                SubscriptionManager.getDefaultDataSubscriptionId()
            }
            if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                val subTm = tm.createForSubscriptionId(dataSubId)
                val name = subTm.networkOperatorName?.trim().orEmpty()
                if (name.isNotEmpty()) return name
                val simName = subTm.simOperatorName?.trim().orEmpty()
                if (simName.isNotEmpty()) return simName
            }
        }
        return tm.networkOperatorName?.trim().orEmpty()
    }

    fun updateConfig(transform: (VexotunConfig) -> VexotunConfig) {
        _config.update { current ->
            val next = transform(current)
            store.save(next)
            next
        }
    }

    fun selectProfile(profileId: String) = updateConfig {
        it.copy(activeProfileId = profileId)
    }

    fun updateActiveProfile(transform: (TunnelProfile) -> TunnelProfile) = updateConfig { cfg ->
        val activeId = cfg.activeProfile()?.id ?: return@updateConfig cfg
        val updatedList = cfg.profiles.map { p ->
            if (p.id == activeId) transform(p) else p
        }
        cfg.copy(profiles = updatedList)
    }

    fun updateProfile(profileId: String, transform: (TunnelProfile) -> TunnelProfile) = updateConfig { cfg ->
        val updatedList = cfg.profiles.map { p ->
            if (p.id == profileId) transform(p) else p
        }
        cfg.copy(profiles = updatedList)
    }

    fun createProfile(mode: TunnelMode): String {
        val count = _config.value.profiles.count { it.mode == mode } + 1
        val newProfile = TunnelProfile(
            name = "${mode.label} #$count",
            mode = mode
        )
        updateConfig { cfg ->
            cfg.copy(
                profiles = cfg.profiles + newProfile,
                activeProfileId = newProfile.id
            )
        }
        return newProfile.id
    }

    fun importFromLinks(rawInput: String): Result<Int> {
        val trimmed = rawInput.trim()
        if (trimmed.startsWith("VXT1:")) {
            return VxtCodec.decode(trimmed.toByteArray(Charsets.UTF_8)).map { imported ->
                updateConfig { cfg ->
                    cfg.copy(
                        profiles = cfg.profiles + imported,
                        activeProfileId = imported.id
                    )
                }
                TunnelState.appendLog("INFO", "Config .vxt diimpor: ${imported.name}")
                1
            }
        }
        val result = LinkParser.parseShareLinks(rawInput)
        return result.map { imported ->
            updateConfig { cfg ->
                cfg.copy(
                    profiles = cfg.profiles + imported,
                    activeProfileId = imported.last().id
                )
            }
            imported.size
        }
    }

    fun duplicateProfile(profileId: String) {
        val original = _config.value.profiles.firstOrNull { it.id == profileId } ?: return
        if (original.isLocked) return
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} (Salinan)"
        )
        updateConfig { cfg ->
            cfg.copy(
                profiles = cfg.profiles + copy,
                activeProfileId = copy.id
            )
        }
    }

    fun importVxtUri(uri: Uri, onResult: ((Result<String>) -> Unit)? = null) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes()
                    } ?: error("Tidak dapat membuka file .vxt")
                    VxtCodec.decode(bytes).getOrThrow()
                }
            }
            val finalResult = result.map { profile ->
                updateConfig { cfg ->
                    cfg.copy(
                        profiles = cfg.profiles + profile,
                        activeProfileId = profile.id
                    )
                }
                TunnelState.appendLog("INFO", "Config .vxt diimpor: ${profile.name}")
                profile.name
            }
            onResult?.invoke(finalResult)
        }
    }

    fun exportVxtBytes(profile: TunnelProfile, lockInfo: VxtLockInfo): ByteArray =
        VxtCodec.encode(profile, lockInfo)

    fun exportVxtString(profile: TunnelProfile, lockInfo: VxtLockInfo): String =
        VxtCodec.encode(profile, lockInfo).toString(Charsets.UTF_8)

    fun runBatchPingCheck() {
        val profiles = _config.value.profiles
        if (_isBatchPinging.value || profiles.isEmpty()) return
        _isBatchPinging.value = true
        viewModelScope.launch {
            val semaphore = Semaphore(5)
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    profiles.map { profile ->
                        async {
                            semaphore.withPermit {
                                val ms = when (profile.mode) {
                                    TunnelMode.SSH -> {
                                        if (profile.ssh.host.isNotBlank()) {
                                            pingTcpMs(profile.ssh.host.trim(), profile.ssh.port, 2500)
                                        } else -1
                                    }
                                    TunnelMode.XRAY -> {
                                        if (profile.xray.address.isNotBlank()) {
                                            pingTcpMs(profile.xray.address.trim(), profile.xray.port, 2500)
                                        } else -1
                                    }
                                }
                                profile.id to ms
                            }
                        }
                    }.awaitAll().toMap()
                }
            }
            _pingResults.value = results
            _isBatchPinging.value = false
        }
    }

    fun deleteProfile(profileId: String) = updateConfig { cfg ->
        val remaining = cfg.profiles.filterNot { it.id == profileId }
        val nextActive = when {
            remaining.isEmpty() -> ""
            cfg.activeProfileId == profileId -> remaining.first().id
            else -> cfg.activeProfileId
        }
        cfg.copy(profiles = remaining, activeProfileId = nextActive)
    }

    fun setSplitMode(mode: SplitTunnelMode) = updateConfig {
        it.copy(splitMode = mode)
    }

    fun toggleSplitPackage(pkg: String) = updateConfig { current ->
        val nextSet = current.splitPackages.toMutableSet()
        if (!nextSet.add(pkg)) {
            nextSet.remove(pkg)
        }
        current.copy(splitPackages = nextSet.toList())
    }

    fun startVpn() {
        store.save(_config.value)
        VexotunVpnService.start(getApplication())
    }

    fun stopVpn() {
        VexotunVpnService.stop(getApplication())
    }

    fun reconnectVpn() {
        VexotunVpnService.reconnect(getApplication())
    }

    fun clearLogs() {
        TunnelState.clearLogs()
    }

    fun runPingCheck() {
        if (_isPinging.value) return
        _isPinging.value = true
        viewModelScope.launch {
            val ms = withContext(Dispatchers.IO) {
                val active = _config.value.activeProfile()
                val connected = TunnelState.state.value.status == ConnectionStatus.CONNECTED
                if (connected) {
                    TunnelState.pingSocks(_config.value.socksPort)
                } else {
                    when (active?.mode) {
                        TunnelMode.SSH -> {
                            if (active.ssh.host.isNotBlank()) {
                                pingTcpMs(active.ssh.host.trim(), active.ssh.port, 3000)
                            } else {
                                pingHttpMs("https://www.gstatic.com/generate_204", 4000)
                            }
                        }
                        TunnelMode.XRAY -> {
                            if (active.xray.address.isNotBlank()) {
                                pingTcpMs(active.xray.address.trim(), active.xray.port, 3000)
                            } else {
                                pingHttpMs("https://www.gstatic.com/generate_204", 4000)
                            }
                        }
                        null -> pingHttpMs("https://www.gstatic.com/generate_204", 4000)
                    }
                }
            }
            TunnelState.setPing(if (ms >= 0) ms else null)
            TunnelState.appendLog(
                if (ms >= 0) "INFO" else "WARN",
                if (ms >= 0) "Ping: ${ms} ms" else "Ping gagal / timeout"
            )
            _isPinging.value = false
        }
    }

    private fun pingTcpMs(host: String, port: Int, timeoutMs: Int): Int {
        if (host.isBlank() || port <= 0) return -1
        return runCatching {
            val start = SystemClock.elapsedRealtime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (SystemClock.elapsedRealtime() - start).toInt()
        }.getOrElse { -1 }
    }

    private fun pingHttpMs(targetUrl: String, timeoutMs: Int): Int {
        return runCatching {
            val start = SystemClock.elapsedRealtime()
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
            }
            try {
                conn.responseCode
                (SystemClock.elapsedRealtime() - start).toInt()
            } finally {
                conn.disconnect()
            }
        }.getOrElse { -1 }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val selfPkg = getApplication<Application>().packageName
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != selfPkg }
                .map { info ->
                    val label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrElse { info.packageName }
                    val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isLauncher = pm.getLaunchIntentForPackage(info.packageName) != null
                    InstalledApp(
                        packageName = info.packageName,
                        label = label,
                        isSystem = isSys,
                        isLauncher = isLauncher
                    )
                }
                .sortedWith(
                    compareByDescending<InstalledApp> { it.isLauncher }
                        .thenBy { it.isSystem }
                        .thenBy { it.label.lowercase() }
                )
            _apps.value = installed
        }
    }
}
