package com.vexotun.app.ui.main

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vexotun.app.data.ConfigStore
import com.vexotun.app.model.LinkParser
import com.vexotun.app.model.SplitTunnelMode
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.TunnelProfile
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.vpn.TunnelState
import com.vexotun.app.vpn.VexotunVpnService
import core.Core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ConfigStore(application)

    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<VexotunConfig> = _config.asStateFlow()

    val runtimeState = TunnelState.state

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    init {
        loadInstalledApps()
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
        val activeId = cfg.activeProfile().id
        val updatedList = cfg.profiles.map { p ->
            if (p.id == activeId) transform(p) else p
        }
        cfg.copy(profiles = updatedList)
    }

    fun addBlankProfile(mode: TunnelMode) = updateConfig { cfg ->
        val newProfile = TunnelProfile(
            name = if (mode == TunnelMode.XRAY) "Xray Baru" else "SSH Baru",
            mode = mode
        )
        cfg.copy(
            profiles = cfg.profiles + newProfile,
            activeProfileId = newProfile.id
        )
    }

    fun importFromLink(rawInput: String): Result<TunnelProfile> {
        val result = LinkParser.parseShareLink(rawInput)
        result.onSuccess { imported ->
            updateConfig { cfg ->
                cfg.copy(
                    profiles = cfg.profiles + imported,
                    activeProfileId = imported.id
                )
            }
        }
        return result
    }

    fun deleteProfile(profileId: String) = updateConfig { cfg ->
        if (cfg.profiles.size <= 1) return@updateConfig cfg
        val remaining = cfg.profiles.filterNot { it.id == profileId }
        val nextActive = if (cfg.activeProfileId == profileId) remaining.first().id else cfg.activeProfileId
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

    fun clearLogs() {
        TunnelState.clearLogs()
    }

    fun runPingCheck() {
        if (_isPinging.value) return
        _isPinging.value = true
        viewModelScope.launch {
            val ms = withContext(Dispatchers.IO) {
                val active = _config.value.activeProfile()
                when (active.mode) {
                    TunnelMode.SSH -> {
                        if (active.ssh.host.isNotBlank()) {
                            Core.pingTCP(active.ssh.host.trim(), active.ssh.port, 3000)
                        } else {
                            Core.pingHTTP("https://www.gstatic.com/generate_204", 4000)
                        }
                    }
                    TunnelMode.XRAY -> {
                        if (active.xray.address.isNotBlank()) {
                            Core.pingTCP(active.xray.address.trim(), active.xray.port, 3000)
                        } else {
                            Core.pingHTTP("https://www.gstatic.com/generate_204", 4000)
                        }
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
                    InstalledApp(
                        packageName = info.packageName,
                        label = label,
                        isSystem = isSys
                    )
                }
                .sortedWith(compareBy<InstalledApp> { it.isSystem }.thenBy { it.label.lowercase() })
            _apps.value = installed
        }
    }
}
