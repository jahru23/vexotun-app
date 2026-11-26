package com.vexotun.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vexotun.app.MainActivity
import com.vexotun.app.R
import com.vexotun.app.data.ConfigStore
import com.vexotun.app.model.CoreSshPayload
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
import hev.htproxy.TProxyService
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
import java.io.File

class VexotunVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private var tunInterface: ParcelFileDescriptor? = null
    private var startJob: Job? = null
    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var activeNetworkHandle: Long? = null

    @Volatile
    private var networkWasLost: Boolean = false

    @Volatile
    private var isStopping: Boolean = false

    private val ipcHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VpnIpc.MSG_REGISTER -> TunnelState.registerClient(msg.replyTo)
                VpnIpc.MSG_UNREGISTER -> TunnelState.unregisterClient(msg.replyTo)
                VpnIpc.MSG_APPEND_LOG -> {
                    val lvl = msg.data.getString("level") ?: "INFO"
                    val text = msg.data.getString("message") ?: ""
                    if (text.isNotEmpty()) TunnelState.appendLog(lvl, text)
                }
                VpnIpc.MSG_CLEAR_LOGS -> TunnelState.clearLogs()
                VpnIpc.MSG_PING_SOCKS -> {
                    val reqId = msg.arg1
                    val socksPort = msg.arg2
                    val replyTo = msg.replyTo ?: return
                    serviceScope.launch {
                        val ms = runCatching {
                            Core.pingSocksHTTP(socksPort, "https://www.gstatic.com/generate_204", 5000)
                        }.getOrElse { -1 }
                        runCatching {
                            replyTo.send(Message.obtain(null, VpnIpc.MSG_PING_RESULT, reqId, ms))
                        }
                    }
                }
                VpnIpc.MSG_SCAN_SLOWDNS -> {
                    val reqId = msg.arg1
                    val payload = msg.data.getString("payload") ?: ""
                    val replyTo = msg.replyTo ?: return
                    serviceScope.launch {
                        val res = runCatching {
                            Core.scanSlowDNS(payload, null)
                        }.getOrElse {
                            "{\"ok\":false,\"error\":\"${it.message ?: "gagal scan"}\"}"
                        }
                        val b = Bundle().apply { putString("result", res) }
                        val reply = Message.obtain(null, VpnIpc.MSG_SCAN_RESULT, reqId, 0).apply {
                            data = b
                        }
                        runCatching { replyTo.send(reply) }
                    }
                }
                VpnIpc.MSG_FETCH_SOCKS -> {
                    val reqId = msg.arg1
                    val socksPort = msg.arg2
                    val url = msg.data.getString("url") ?: ""
                    val replyTo = msg.replyTo ?: return
                    serviceScope.launch {
                        val res = runCatching {
                            Core.fetchSocksURL(socksPort, url, 8000)
                        }
                        val b = Bundle().apply {
                            putBoolean("ok", res.isSuccess)
                            putString("body", res.getOrDefault(""))
                            putString("error", res.exceptionOrNull()?.message ?: "")
                        }
                        val reply = Message.obtain(null, VpnIpc.MSG_FETCH_RESULT, reqId, 0).apply {
                            data = b
                        }
                        runCatching { replyTo.send(reply) }
                    }
                }
                VpnIpc.MSG_SPEED_SOCKS -> {
                    val reqId = msg.arg1
                    val socksPort = msg.arg2
                    val url = msg.data.getString("url") ?: ""
                    val replyTo = msg.replyTo ?: return
                    serviceScope.launch {
                        val res = runCatching {
                            Core.speedTestSocks(socksPort, url, 12000)
                        }.getOrElse {
                            "{\"ok\":false,\"error\":\"${it.message ?: "gagal speed test"}\"}"
                        }
                        val b = Bundle().apply { putString("result", res) }
                        val reply = Message.obtain(null, VpnIpc.MSG_SPEED_RESULT, reqId, 0).apply {
                            data = b
                        }
                        runCatching { replyTo.send(reply) }
                    }
                }
            }
        }
    }

    private val ipcMessenger by lazy { Messenger(ipcHandler) }

    override fun onCreate() {
        super.onCreate()
        TunnelState.init(this, serviceProcess = true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == VpnIpc.ACTION_BIND_IPC) {
            TunnelState.init(this, serviceProcess = true)
            return ipcMessenger.binder
        }
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TunnelState.init(this, serviceProcess = true)
        when (intent?.action) {
            ACTION_STOP -> {
                isStopping = true
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                isStopping = false
                startForegroundNotification("Menyambung ulang...")
                reconnectTunnel()
                return START_STICKY
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

    private fun reconnectTunnel() {
        startJob?.cancel()
        startJob = null
        serviceScope.launch {
            TunnelState.appendLog("INFO", "Menyambung ulang...")
            cleanupResources()
            if (isStopping) return@launch
            val config = ConfigStore(this@VexotunVpnService).load()
            startTunnel(config)
        }
    }

    private fun startTunnel(config: VexotunConfig) {
        startJob?.cancel()
        startJob = serviceScope.launch {
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
            try {
                val protoLabel = if (profile.mode == TunnelMode.XRAY) {
                    profile.xray.protocol.name
                } else {
                    "SSH"
                }
                TunnelState.setStatus(ConnectionStatus.CONNECTING, "Menghubungkan ke ${profile.name}...")
                TunnelState.appendLog("INFO", "Menghubungkan ${profile.name} ($protoLabel)...")

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
                    profile.xray.network != XrayTransport.GRPC &&
                    profile.xray.network != XrayTransport.XHTTP

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

                val underlyingDns = detectUnderlyingDnsServers(this@VexotunVpnService)
                val coreConfig = CoreTunnelConfig(
                    mode = if (profile.mode == TunnelMode.SSH) "ssh" else "xray",
                    mtu = config.mtu.coerceIn(1280, 1500),
                    socksPort = config.socksPort,
                    httpPort = config.httpPort,
                    allowLan = config.allowLan,
                    ssh = CoreSshPayload(
                        host = profile.ssh.host.trim(),
                        port = if (profile.ssh.port > 0) profile.ssh.port else if (profile.ssh.transport == com.vexotun.app.model.SshTransport.SLOWDNS) 443 else 22,
                        username = profile.ssh.username,
                        password = profile.ssh.password,
                        privateKey = profile.ssh.privateKey,
                        transport = profile.ssh.transport.wireValue,
                        proxyHost = profile.ssh.proxyHost.trim(),
                        proxyPort = if (profile.ssh.proxyPort > 0) profile.ssh.proxyPort else 8080,
                        sni = profile.ssh.sni.trim(),
                        payload = profile.ssh.payload,
                        wsPath = profile.ssh.wsPath.trim(),
                        wsHost = profile.ssh.wsHost.trim(),
                        dnsServer = profile.ssh.dnsServer.trim().ifBlank { "Auto" },
                        fallbackDns = underlyingDns,
                        dnsType = profile.ssh.dnsType.wireValue,
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

                TunnelState.appendLog("DEBUG", "Memulai ${profile.mode.label}.")
                Core.prepare(json.encodeToString(coreConfig), protector, logger)

                if (isStopping) {
                    runCatching { Core.stop() }
                    return@launch
                }

                if (canUseMux) {
                    val muxProbe = Core.pingSocksHTTP(config.socksPort, "https://www.gstatic.com/generate_204", 3500)
                    if (muxProbe < 0 && !isStopping) {
                        TunnelState.appendLog("WARN", "Server menolak Mux, beralih otomatis ke mode tanpa Mux...")
                        runCatching { Core.stop() }
                        val fallbackJson = XrayConfigBuilder.buildJson(
                            profile = profile.xray,
                            dnsServer = config.dnsServer,
                            bypassLan = config.bypassLan,
                            muxEnabled = false
                        )
                        Core.prepare(json.encodeToString(coreConfig.copy(xrayConfigJson = fallbackJson)), protector, logger)
                    } else if (muxProbe >= 0) {
                        TunnelState.appendLog("INFO", "Xray Mux aktif (${muxProbe} ms).")
                    }
                }

                if (isStopping) {
                    runCatching { Core.stop() }
                    return@launch
                }

                val effectiveMtu = config.mtu.coerceIn(1280, 1500)
                val builder = Builder()
                    .setSession("Vexotun")
                    .setMtu(effectiveMtu)
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fd00:1::2", 64)
                    .addRoute("::", 0)
                    .addDnsServer(config.dnsServer.ifBlank { "8.8.8.8" })

                applySplitTunneling(builder, config)

                val pfd = builder.establish()
                    ?: throw IllegalStateException("Gagal membuat interface VPN")
                tunInterface = pfd
                TunnelState.appendLog("DEBUG", "VPN interface established")

                val tproxyConf = writeHevConfig(effectiveMtu, config.socksPort)
                if (!TProxyService.TProxyStartService(tproxyConf.absolutePath, pfd.fd)) {
                    throw IllegalStateException("Gagal memulai bridge TUN (hev-socks5-tunnel)")
                }

                TunnelState.appendLog("DEBUG", "Engine & hev-socks5-tunnel active")
                TunnelState.appendLog("INFO", "Terhubung • ${profile.name}")

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
                val rawErr = t.message.orEmpty()
                val friendlyErr = friendlyTunnelError(rawErr)
                if (rawErr.isNotBlank() && rawErr != friendlyErr) {
                    TunnelState.appendLog("DEBUG", "Detail error: $rawErr")
                }
                TunnelState.appendLog("ERROR", friendlyErr)
                TunnelState.setStatus(ConnectionStatus.ERROR, friendlyErr)
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
            while (isActive && !isStopping) {
                val tStats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
                if (tStats != null && tStats.size >= 4) {
                    TunnelState.updateTraffic(tStats[1], tStats[3])
                }
                val engineAlive = runCatching {
                    Core.isRunning() && TProxyService.TProxyIsRunning()
                }.getOrElse { false }
                if (!engineAlive && !isStopping) {
                    val cfg = ConfigStore(this@VexotunVpnService).load()
                    if (cfg.autoReconnect) {
                        TunnelState.appendLog("WARN", "Tunnel berhenti merespons, menyambung ulang otomatis...")
                        scheduleReconnect("Menyambung ulang tunnel yang terputus...")
                    } else {
                        TunnelState.appendLog("ERROR", "Tunnel terputus.")
                        TunnelState.setStatus(ConnectionStatus.ERROR, "Tunnel terputus")
                        cleanupResources()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return@launch
                }
                delay(1000L)
            }
        }
    }

    private fun stopTunnel() {
        isStopping = true
        startJob?.cancel()
        startJob = null
        serviceScope.launch {
            TunnelState.appendLog("DEBUG", "Memutus koneksi...")
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
        networkWasLost = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isStopping) return
                val handle = network.networkHandle
                val prev = activeNetworkHandle
                if (prev == null && !networkWasLost) {
                    activeNetworkHandle = handle
                    return
                }
                if (prev != handle || networkWasLost) {
                    networkWasLost = false
                    activeNetworkHandle = handle
                    scheduleReconnect()
                }
            }

            override fun onLost(network: Network) {
                if (isStopping) return
                if (activeNetworkHandle == network.networkHandle) {
                    activeNetworkHandle = null
                    networkWasLost = true
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

    private fun scheduleReconnect(reason: String = "Jaringan berubah, menyambung ulang...") {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(1200L)
            if (isStopping) return@launch
            TunnelState.appendLog("INFO", reason)
            cleanupResources(cancelReconnect = false)
            if (isStopping) return@launch
            val config = ConfigStore(this@VexotunVpnService).load()
            startTunnel(config)
        }
    }

    private fun writeHevConfig(mtu: Int, socksPort: Int): File {
        val file = File(cacheDir, "tproxy.conf")
        file.writeText(
            """
            tunnel:
              mtu: $mtu
              ipv4: 10.0.0.2
              ipv6: 'fd00:1::2'
            socks5:
              port: $socksPort
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 32768
              tcp-buffer-size: 32768
              udp-recv-buffer-size: 131072
              udp-copy-buffer-nums: 4
              max-session-count: 512
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 30000
              log-level: error
            """.trimIndent()
        )
        return file
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

    private fun cleanupResources(cancelReconnect: Boolean = true) {
        if (cancelReconnect) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        unregisterNetworkCallback()
        releaseWakeLock()
        statsJob?.cancel()
        statsJob = null
        runCatching {
            if (TProxyService.TProxyIsRunning()) {
                TProxyService.TProxyStopService()
            }
        }
        runCatching { Core.stop() }
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            serviceScope.launch {
                runCatching { Core.trimMemory() }
            }
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the foreground VPN service alive when the UI task is backgrounded or swiped away.
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
        const val ACTION_RECONNECT = "com.vexotun.app.RECONNECT_VPN"
        private const val CHANNEL_ID = "vexotun_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        fun friendlyTunnelError(raw: String): String {
            val low = raw.lowercase()
            return when {
                low.isBlank() -> "Gagal menghubungkan tunnel"
                "unable to authenticate" in low || "authentication failed" in low || "permission denied" in low ->
                    "Autentikasi gagal: periksa username/password atau masa aktif akun"
                "no reachable dns tunnel" in low || "tidak mendapat respons" in low ->
                    "SlowDNS tidak merespons: coba pindah resolver atau tipe DNS (mis. AAAA)"
                "tls" in low || "handshake" in low || "certificate" in low || "sni" in low ->
                    "Handshake TLS/SSL gagal: periksa SNI atau port server"
                "i/o timeout" in low || "deadline exceeded" in low || "timed out" in low ->
                    "Koneksi timeout: server atau jaringan tidak merespons"
                "connection refused" in low || "no route to host" in low || "network is unreachable" in low ->
                    "Server tidak terjangkau atau port sedang tidak aktif"
                else -> raw
            }
        }

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

        fun detectUnderlyingDnsServers(context: Context): String {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return ""
            val v4 = LinkedHashSet<String>()
            val v6 = LinkedHashSet<String>()
            val sortedNetworks = cm.allNetworks.sortedByDescending { net ->
                val caps = cm.getNetworkCapabilities(net)
                when {
                    caps == null -> 0
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                    else -> 0
                }
            }
            for (network in sortedNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) {
                    continue
                }
                val lp = cm.getLinkProperties(network) ?: continue
                for (addr in lp.dnsServers) {
                    val host = (addr.hostAddress ?: continue).substringBefore('%')
                    if (addr is java.net.Inet4Address) {
                        v4.add("$host:53")
                    } else if (addr is java.net.Inet6Address) {
                        v6.add("[$host]:53")
                    }
                }
            }
            return (v4 + v6).joinToString(",")
        }

        fun stop(context: Context) {
            val intent = Intent(context, VexotunVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun reconnect(context: Context) {
            val intent = Intent(context, VexotunVpnService::class.java).apply {
                action = ACTION_RECONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
