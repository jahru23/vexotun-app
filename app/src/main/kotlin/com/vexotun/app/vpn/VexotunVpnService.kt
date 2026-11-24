package com.vexotun.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vexotun.app.MainActivity
import com.vexotun.app.R
import com.vexotun.app.data.ConfigStore
import com.vexotun.app.model.CoreSshPayload
import com.vexotun.app.model.CoreStats
import com.vexotun.app.model.CoreTunnelConfig
import com.vexotun.app.model.CoreXrayPayload
import com.vexotun.app.model.SplitTunnelMode
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.model.XrayConfigBuilder
import com.vexotun.app.model.XrayTransport
import core.Core
import core.LogHandler
import core.SocketProtector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class VexotunVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private var tunInterface: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var activeNetworkHandle: Long? = null

    @Volatile
    private var isStopping: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isStopping = true
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                isStopping = false
                startForegroundNotification("Menghubungkan...")
                val config = ConfigStore(this).load()
                startTunnel(config)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(config: VexotunConfig) {
        serviceScope.launch {
            val profile = config.activeProfile()
            if (profile == null) {
                TunnelState.setStatus(ConnectionStatus.ERROR, "Belum ada config yang dipilih")
                TunnelState.appendLog("ERROR", "Gagal memulai: belum ada config")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            if (profile.lockInfo?.isExpired() == true) {
                TunnelState.setStatus(ConnectionStatus.ERROR, "Config .vxt sudah kedaluwarsa")
                TunnelState.appendLog("ERROR", "Config .vxt sudah kedaluwarsa")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            var detachedFd = -1
            try {
                val protoLabel = if (profile.mode == TunnelMode.XRAY) {
                    profile.xray.protocol.name
                } else {
                    "SSH"
                }
                TunnelState.setStatus(ConnectionStatus.CONNECTING, "Menghubungkan ke ${profile.name}...")
                TunnelState.appendLog("INFO", "Connecting $protoLabel")

                val builder = Builder()
                    .setSession("Vexotun")
                    .setMtu(config.mtu.coerceIn(1280, 1500))
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(config.dnsServer.ifBlank { "8.8.8.8" })

                applySplitTunneling(builder, config)

                val pfd = builder.establish()
                    ?: throw IllegalStateException("Gagal membuat interface VPN")
                TunnelState.appendLog("INFO", "VPN siap.")

                val xrayPayload = if (profile.mode == TunnelMode.XRAY && profile.xray.useCustomPayload) {
                    CoreXrayPayload(
                        enabled = true,
                        targetHost = profile.xray.address.trim(),
                        targetPort = profile.xray.port,
                        proxyHost = profile.xray.proxyHost.trim(),
                        proxyPort = profile.xray.proxyPort,
                        payload = profile.xray.payload,
                        sni = profile.xray.sni.trim().ifBlank { profile.xray.host.trim() }
                    )
                } else {
                    CoreXrayPayload()
                }

                val canUseMux = profile.mode == TunnelMode.XRAY &&
                    profile.xray.muxEnabled &&
                    profile.xray.flow.isBlank() &&
                    profile.xray.network != XrayTransport.GRPC

                val xrayJson = if (profile.mode == TunnelMode.XRAY) {
                    XrayConfigBuilder.buildJson(
                        profile = profile.xray,
                        dnsServer = config.dnsServer,
                        bypassLan = config.bypassLan,
                        muxEnabled = canUseMux
                    )
                } else {
                    ""
                }

                val coreConfig = CoreTunnelConfig(
                    mode = if (profile.mode == TunnelMode.SSH) "ssh" else "xray",
                    mtu = config.mtu.coerceIn(1280, 1500),
                    socksPort = config.socksPort,
                    httpPort = config.httpPort,
                    allowLan = config.allowLan,
                    ssh = CoreSshPayload(
                        host = profile.ssh.host.trim(),
                        port = profile.ssh.port,
                        username = profile.ssh.username,
                        password = profile.ssh.password,
                        privateKey = profile.ssh.privateKey,
                        transport = profile.ssh.transport.wireValue,
                        proxyHost = profile.ssh.proxyHost.trim(),
                        proxyPort = profile.ssh.proxyPort,
                        sni = profile.ssh.sni.trim(),
                        payload = profile.ssh.payload,
                        wsPath = profile.ssh.wsPath.trim(),
                        wsHost = profile.ssh.wsHost.trim(),
                        dnsServer = profile.ssh.dnsServer.trim().ifBlank { "8.8.8.8" },
                        nameserver = profile.ssh.nameserver.trim(),
                        publicKey = profile.ssh.publicKey.trim()
                    ),
                    xrayConfigJson = xrayJson,
                    xrayPayload = xrayPayload
                )

                val protector = SocketProtector { fd -> protect(fd) }
                val logger = LogHandler { level, message ->
                    TunnelState.appendLog(level, message)
                }

                // Detach FD so tun2socks owns and closes it without triggering Android fdsan SIGABRT.
                detachedFd = pfd.detachFd()
                tunInterface = null

                TunnelState.appendLog("INFO", "Memulai ${profile.mode.label}.")
                Core.start(detachedFd, json.encodeToString(coreConfig), protector, logger)
                detachedFd = -1

                if (canUseMux) {
                    val muxProbe = Core.pingSocksHTTP(config.socksPort, "https://www.gstatic.com/generate_204", 3500)
                    if (muxProbe < 0 && !isStopping) {
                        TunnelState.appendLog("WARN", "Server menolak Mux, beralih otomatis ke mode tanpa Mux...")
                        runCatching { Core.stop() }
                        val fallbackPfd = builder.establish()
                            ?: throw IllegalStateException("Gagal membuat ulang interface VPN")
                        detachedFd = fallbackPfd.detachFd()
                        val fallbackJson = XrayConfigBuilder.buildJson(
                            profile = profile.xray,
                            dnsServer = config.dnsServer,
                            bypassLan = config.bypassLan,
                            muxEnabled = false
                        )
                        Core.start(detachedFd, json.encodeToString(coreConfig.copy(xrayConfigJson = fallbackJson)), protector, logger)
                        detachedFd = -1
                    } else if (muxProbe >= 0) {
                        TunnelState.appendLog("INFO", "Xray Mux aktif (${muxProbe} ms).")
                    }
                }

                TunnelState.appendLog("INFO", "Engine aktif.")
                TunnelState.appendLog("INFO", "Rute VPN aktif.")
                TunnelState.appendLog("INFO", "Tersambung.")

                TunnelState.setStatus(ConnectionStatus.CONNECTED, "Terhubung • ${profile.name}")
                updateNotification("Terhubung • ${profile.name}")
                if (config.wakeLock) {
                    acquireWakeLock()
                }
                startStatsLoop()
                if (config.autoReconnect) {
                    registerNetworkCallback()
                }
            } catch (t: Throwable) {
                if (detachedFd >= 0 && !Core.isRunning()) {
                    runCatching { ParcelFileDescriptor.adoptFd(detachedFd).close() }
                }
                val errMsg = t.message ?: "Gagal menghubungkan tunnel"
                TunnelState.appendLog("ERROR", errMsg)
                TunnelState.setStatus(ConnectionStatus.ERROR, errMsg)
                cleanupResources()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun applySplitTunneling(builder: Builder, config: VexotunConfig) {
        when (config.splitMode) {
            SplitTunnelMode.ALL_APPS -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
            SplitTunnelMode.INCLUDE_ONLY -> {
                val targets = config.splitPackages.filter { it != packageName }
                if (targets.isEmpty()) {
                    runCatching { builder.addDisallowedApplication(packageName) }
                } else {
                    targets.forEach { pkg ->
                        runCatching { builder.addAllowedApplication(pkg) }
                    }
                }
            }
            SplitTunnelMode.EXCLUDE_SELECTED -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                config.splitPackages.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && Core.isRunning()) {
                runCatching {
                    val raw = Core.getStats()
                    val stats = json.decodeFromString<CoreStats>(raw)
                    TunnelState.updateTraffic(stats.bytesUp, stats.bytesDown)
                }
                delay(1000L)
            }
        }
    }

    private fun stopTunnel() {
        isStopping = true
        serviceScope.launch {
            TunnelState.appendLog("INFO", "Memutus koneksi...")
            cleanupResources()
            TunnelState.setStatus(ConnectionStatus.DISCONNECTED, "Terputus")
            TunnelState.appendLog("INFO", "Koneksi terputus.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        activeNetworkHandle = cm.activeNetwork?.networkHandle
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isStopping) return
                val handle = network.networkHandle
                val prev = activeNetworkHandle
                if (prev == null) {
                    activeNetworkHandle = handle
                    return
                }
                if (prev != handle) {
                    activeNetworkHandle = handle
                    scheduleReconnect()
                }
            }

            override fun onLost(network: Network) {
                if (isStopping) return
                if (activeNetworkHandle == network.networkHandle) {
                    activeNetworkHandle = null
                }
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(1200L)
            if (isStopping) return@launch
            TunnelState.appendLog("INFO", "Jaringan berubah, menyambung ulang...")
            statsJob?.cancel()
            statsJob = null
            runCatching { Core.stop() }
            runCatching { tunInterface?.close() }
            tunInterface = null
            val config = ConfigStore(this@VexotunVpnService).load()
            startTunnel(config)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vexotun::TunnelWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun cleanupResources() {
        reconnectJob?.cancel()
        reconnectJob = null
        unregisterNetworkCallback()
        releaseWakeLock()
        statsJob?.cancel()
        statsJob = null
        runCatching { Core.stop() }
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        cleanupResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification(statusText: String) {
        ensureNotificationChannel()
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VexotunVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vexotun")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Putuskan", stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Status VPN Vexotun",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm?.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val ACTION_START = "com.vexotun.app.START_VPN"
        const val ACTION_STOP = "com.vexotun.app.STOP_VPN"
        private const val CHANNEL_ID = "vexotun_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, VexotunVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VexotunVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
