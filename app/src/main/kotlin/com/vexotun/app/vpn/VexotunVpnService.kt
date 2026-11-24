package com.vexotun.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.vexotun.app.MainActivity
import com.vexotun.app.R
import com.vexotun.app.data.ConfigStore
import com.vexotun.app.model.CoreSshPayload
import com.vexotun.app.model.CoreStats
import com.vexotun.app.model.CoreTunnelConfig
import com.vexotun.app.model.SplitTunnelMode
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.model.XrayConfigBuilder
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
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
            try {
                TunnelState.setStatus(ConnectionStatus.CONNECTING, "Menghubungkan ke ${profile.name}...")
                TunnelState.appendLog("INFO", "Memulai ${profile.mode.label}: ${profile.name}")

                val builder = Builder()
                    .setSession("Vexotun")
                    .setMtu(config.mtu.coerceIn(1280, 1500))
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(config.dnsServer.ifBlank { "8.8.8.8" })

                applySplitTunneling(builder, config)

                val pfd = builder.establish()
                    ?: throw IllegalStateException("Gagal membuat interface VPN")
                tunInterface = pfd

                val xrayJson = if (profile.mode == TunnelMode.XRAY) {
                    XrayConfigBuilder.buildJson(profile.xray)
                } else {
                    ""
                }

                val coreConfig = CoreTunnelConfig(
                    mode = if (profile.mode == TunnelMode.SSH) "ssh" else "xray",
                    mtu = config.mtu.coerceIn(1280, 1500),
                    socksPort = config.socksPort,
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
                        wsHost = profile.ssh.wsHost.trim()
                    ),
                    xrayConfigJson = xrayJson
                )

                val protector = SocketProtector { fd -> protect(fd) }
                val logger = LogHandler { level, message ->
                    TunnelState.appendLog(level, message)
                }

                Core.start(pfd.fd, json.encodeToString(coreConfig), protector, logger)

                TunnelState.setStatus(ConnectionStatus.CONNECTED, "Terhubung • ${profile.name}")
                updateNotification("Terhubung • ${profile.name}")
                startStatsLoop()
            } catch (t: Throwable) {
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
        serviceScope.launch {
            cleanupResources()
            TunnelState.setStatus(ConnectionStatus.DISCONNECTED, "Terputus")
            TunnelState.updateTraffic(0L, 0L)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanupResources() {
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
