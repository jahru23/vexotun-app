package com.vexotun.app.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object VpnIpc {
    const val ACTION_BIND_IPC = "com.vexotun.app.BIND_VPN_IPC"

    // UI -> :vpn
    const val MSG_REGISTER = 1
    const val MSG_UNREGISTER = 2
    const val MSG_APPEND_LOG = 3
    const val MSG_CLEAR_LOGS = 4
    const val MSG_PING_SOCKS = 5
    const val MSG_SCAN_SLOWDNS = 6
    const val MSG_FETCH_SOCKS = 8
    const val MSG_SPEED_SOCKS = 9

    // :vpn -> UI
    const val MSG_SYNC_SNAPSHOT = 10
    const val MSG_STATUS_CHANGED = 11
    const val MSG_TRAFFIC_UPDATED = 12
    const val MSG_LOG_APPENDED = 13
    const val MSG_LOGS_CLEARED = 14
    const val MSG_PING_RESULT = 15
    const val MSG_SCAN_RESULT = 16
    const val MSG_FETCH_RESULT = 17
    const val MSG_SPEED_RESULT = 18
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class LogEntry(
    val id: Long,
    val level: String,
    val message: String,
    val timestamp: String = currentLogTime()
)

@Serializable
private data class IpcLogDto(
    val id: Long,
    val level: String,
    val message: String,
    val timestamp: String
)

private val logTimeFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
}

private fun currentLogTime(): String = logTimeFormatter.get()!!.format(Date())

data class VpnRuntimeState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val statusMessage: String = "Disconnected",
    val logs: List<LogEntry> = emptyList()
)

data class TrafficStatsState(
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L,
    val speedUpBps: Long = 0L,
    val speedDownBps: Long = 0L,
    val pingMs: Int? = null
)

object TunnelState {
    private const val MAX_LOGS = 200
    private const val MAX_DEBUG_FILE_BYTES = 512L * 1024L
    private val nextLogId = AtomicLong(1L)
    private val nextReqId = AtomicInteger(1)
    private val logIoExecutor = Executors.newSingleThreadExecutor()
    private val ipcJson = Json { ignoreUnknownKeys = true }

    @Volatile
    private var debugLogFile: File? = null

    @Volatile
    private var isServiceProcess: Boolean = false

    private val _state = MutableStateFlow(VpnRuntimeState())
    val state: StateFlow<VpnRuntimeState> = _state.asStateFlow()

    private val _trafficState = MutableStateFlow(TrafficStatsState())
    val trafficState: StateFlow<TrafficStatsState> = _trafficState.asStateFlow()

    @Volatile
    private var lastSampleTimeMs: Long = 0L

    @Volatile
    private var lastBytesUp: Long = 0L

    @Volatile
    private var lastBytesDown: Long = 0L

    // Server (:vpn process) IPC clients
    private val registeredClients = CopyOnWriteArraySet<Messenger>()

    // UI process IPC state
    @Volatile
    private var serviceMessenger: Messenger? = null

    @Volatile
    private var bindRequested = false

    private val pendingPings = ConcurrentHashMap<Int, CompletableDeferred<Int>>()
    private val pendingScans = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val pendingFetches = ConcurrentHashMap<Int, CompletableDeferred<Result<String>>>()
    private val pendingSpeeds = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    private val uiIncomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VpnIpc.MSG_SYNC_SNAPSHOT -> applySnapshot(msg.data)
                VpnIpc.MSG_STATUS_CHANGED -> {
                    val b = msg.data
                    val status = ConnectionStatus.entries.getOrElse(b.getInt("statusOrdinal", 0)) {
                        ConnectionStatus.DISCONNECTED
                    }
                    val message = b.getString("statusMessage") ?: ""
                    applyStatusLocal(status, message)
                }
                VpnIpc.MSG_TRAFFIC_UPDATED -> {
                    val b = msg.data
                    _trafficState.update {
                        it.copy(
                            bytesUp = b.getLong("bytesUp", 0L),
                            bytesDown = b.getLong("bytesDown", 0L),
                            speedUpBps = b.getLong("speedUpBps", 0L),
                            speedDownBps = b.getLong("speedDownBps", 0L),
                            pingMs = if (b.containsKey("pingMs")) b.getInt("pingMs") else null
                        )
                    }
                }
                VpnIpc.MSG_LOG_APPENDED -> {
                    val b = msg.data
                    val entry = LogEntry(
                        id = nextLogId.getAndIncrement(),
                        level = b.getString("level") ?: "INFO",
                        message = b.getString("message") ?: "",
                        timestamp = b.getString("timestamp") ?: currentLogTime()
                    )
                    appendLocalLogEntry(entry)
                }
                VpnIpc.MSG_LOGS_CLEARED -> {
                    _state.update { it.copy(logs = emptyList()) }
                }
                VpnIpc.MSG_PING_RESULT -> {
                    pendingPings.remove(msg.arg1)?.complete(msg.arg2)
                }
                VpnIpc.MSG_SCAN_RESULT -> {
                    val res = msg.data.getString("result")
                        ?: "{\"ok\":false,\"error\":\"Respon scan kosong\"}"
                    pendingScans.remove(msg.arg1)?.complete(res)
                }
                VpnIpc.MSG_FETCH_RESULT -> {
                    val ok = msg.data.getBoolean("ok", false)
                    val body = msg.data.getString("body") ?: ""
                    val err = msg.data.getString("error") ?: "Gagal mengambil data"
                    val res = if (ok) Result.success(body) else Result.failure(IllegalStateException(err))
                    pendingFetches.remove(msg.arg1)?.complete(res)
                }
                VpnIpc.MSG_SPEED_RESULT -> {
                    val res = msg.data.getString("result")
                        ?: "{\"ok\":false,\"error\":\"Respon speed test kosong\"}"
                    pendingSpeeds.remove(msg.arg1)?.complete(res)
                }
            }
        }
    }

    private val uiReplyMessenger by lazy { Messenger(uiIncomingHandler) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val remote = service?.let { Messenger(it) }
            serviceMessenger = remote
            sendToService(VpnIpc.MSG_REGISTER, replyTo = uiReplyMessenger)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            failAllPendingRequests()
            val curStatus = _state.value.status
            if (curStatus == ConnectionStatus.CONNECTED || curStatus == ConnectionStatus.CONNECTING) {
                applyStatusLocal(ConnectionStatus.DISCONNECTED, "Terputus")
            }
        }
    }

    fun init(context: Context, serviceProcess: Boolean = false) {
        if (serviceProcess) {
            isServiceProcess = true
        }
        if (debugLogFile != null) return
        val dir = File(context.applicationContext.filesDir, "logs")
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            debugLogFile = File(dir, "vexotun-debug.log")
        }
    }

    fun bind(context: Context) {
        if (isServiceProcess || bindRequested) return
        init(context)
        val intent = Intent(context.applicationContext, VexotunVpnService::class.java).apply {
            action = VpnIpc.ACTION_BIND_IPC
        }
        runCatching {
            bindRequested = context.applicationContext.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    fun unbind(context: Context) {
        if (isServiceProcess || !bindRequested) return
        sendToService(VpnIpc.MSG_UNREGISTER, replyTo = uiReplyMessenger)
        runCatching { context.applicationContext.unbindService(serviceConnection) }
        bindRequested = false
        serviceMessenger = null
        failAllPendingRequests()
    }

    private fun failAllPendingRequests() {
        pendingPings.keys.toList().forEach { key ->
            pendingPings.remove(key)?.complete(-1)
        }
        pendingScans.keys.toList().forEach { key ->
            pendingScans.remove(key)?.complete("{\"ok\":false,\"error\":\"Koneksi ke layanan VPN terputus\"}")
        }
        pendingFetches.keys.toList().forEach { key ->
            pendingFetches.remove(key)?.complete(Result.failure(IllegalStateException("Koneksi ke layanan VPN terputus")))
        }
        pendingSpeeds.keys.toList().forEach { key ->
            pendingSpeeds.remove(key)?.complete("{\"ok\":false,\"error\":\"Koneksi ke layanan VPN terputus\"}")
        }
    }

    private fun sendToService(
        what: Int,
        arg1: Int = 0,
        arg2: Int = 0,
        data: Bundle? = null,
        replyTo: Messenger? = null
    ): Boolean {
        val remote = serviceMessenger ?: return false
        val msg = Message.obtain(null, what, arg1, arg2).apply {
            this.replyTo = replyTo
            if (data != null) this.data = data
        }
        return runCatching {
            remote.send(msg)
            true
        }.getOrElse {
            serviceMessenger = null
            false
        }
    }

    suspend fun pingSocks(socksPort: Int, timeoutMs: Long = 6500L): Int {
        val reqId = nextReqId.getAndIncrement()
        val deferred = CompletableDeferred<Int>()
        pendingPings[reqId] = deferred
        if (!sendToService(VpnIpc.MSG_PING_SOCKS, arg1 = reqId, arg2 = socksPort, replyTo = uiReplyMessenger)) {
            pendingPings.remove(reqId)
            return -1
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            pendingPings.remove(reqId)
            -1
        }
    }

    suspend fun scanSlowDns(payloadJson: String, timeoutMs: Long = 45000L): String {
        val reqId = nextReqId.getAndIncrement()
        val deferred = CompletableDeferred<String>()
        pendingScans[reqId] = deferred
        val bundle = Bundle().apply { putString("payload", payloadJson) }
        if (!sendToService(VpnIpc.MSG_SCAN_SLOWDNS, arg1 = reqId, data = bundle, replyTo = uiReplyMessenger)) {
            pendingScans.remove(reqId)
            return "{\"ok\":false,\"error\":\"Layanan core VPN belum siap\"}"
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            pendingScans.remove(reqId)
            "{\"ok\":false,\"error\":\"Timeout memindai DNS\"}"
        }
    }

    suspend fun fetchSocksUrl(socksPort: Int, targetUrl: String, timeoutMs: Long = 10000L): Result<String> {
        val reqId = nextReqId.getAndIncrement()
        val deferred = CompletableDeferred<Result<String>>()
        pendingFetches[reqId] = deferred
        val bundle = Bundle().apply { putString("url", targetUrl) }
        if (!sendToService(VpnIpc.MSG_FETCH_SOCKS, arg1 = reqId, arg2 = socksPort, data = bundle, replyTo = uiReplyMessenger)) {
            pendingFetches.remove(reqId)
            return Result.failure(IllegalStateException("Layanan VPN belum terhubung"))
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            pendingFetches.remove(reqId)
            Result.failure(IllegalStateException("Request timeout"))
        }
    }

    suspend fun speedTestSocks(socksPort: Int, targetUrl: String, timeoutMs: Long = 15000L): String {
        val reqId = nextReqId.getAndIncrement()
        val deferred = CompletableDeferred<String>()
        pendingSpeeds[reqId] = deferred
        val bundle = Bundle().apply { putString("url", targetUrl) }
        if (!sendToService(VpnIpc.MSG_SPEED_SOCKS, arg1 = reqId, arg2 = socksPort, data = bundle, replyTo = uiReplyMessenger)) {
            pendingSpeeds.remove(reqId)
            return "{\"ok\":false,\"error\":\"Layanan VPN belum terhubung\"}"
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            pendingSpeeds.remove(reqId)
            "{\"ok\":false,\"error\":\"Tes kecepatan timeout\"}"
        }
    }

    // ---- Server (:vpn) IPC broadcast helpers ----

    fun registerClient(client: Messenger?) {
        if (client == null) return
        registeredClients.add(client)
        sendSnapshotTo(client)
    }

    fun unregisterClient(client: Messenger?) {
        if (client == null) return
        registeredClients.remove(client)
    }

    private fun sendSnapshotTo(client: Messenger) {
        val s = _state.value
        val t = _trafficState.value
        val logsJson = runCatching {
            ipcJson.encodeToString(s.logs.map { IpcLogDto(it.id, it.level, it.message, it.timestamp) })
        }.getOrElse { "[]" }
        val bundle = Bundle().apply {
            putInt("statusOrdinal", s.status.ordinal)
            putString("statusMessage", s.statusMessage)
            putLong("bytesUp", t.bytesUp)
            putLong("bytesDown", t.bytesDown)
            putLong("speedUpBps", t.speedUpBps)
            putLong("speedDownBps", t.speedDownBps)
            if (t.pingMs != null) putInt("pingMs", t.pingMs)
            putString("logsJson", logsJson)
        }
        sendToClient(client, VpnIpc.MSG_SYNC_SNAPSHOT, bundle)
    }

    private fun sendToClient(client: Messenger, what: Int, data: Bundle? = null) {
        val msg = Message.obtain(null, what).apply {
            if (data != null) this.data = data
        }
        if (runCatching { client.send(msg) }.isFailure) {
            registeredClients.remove(client)
        }
    }

    private fun broadcast(what: Int, data: Bundle? = null) {
        if (!isServiceProcess || registeredClients.isEmpty()) return
        registeredClients.forEach { client ->
            sendToClient(client, what, data)
        }
    }

    private fun applySnapshot(b: Bundle) {
        val status = ConnectionStatus.entries.getOrElse(b.getInt("statusOrdinal", 0)) {
            ConnectionStatus.DISCONNECTED
        }
        val statusMessage = b.getString("statusMessage") ?: "Disconnected"
        val logsJson = b.getString("logsJson") ?: "[]"
        val remoteLogs = runCatching {
            ipcJson.decodeFromString<List<IpcLogDto>>(logsJson).map {
                LogEntry(it.id, it.level, it.message, it.timestamp)
            }
        }.getOrElse { emptyList() }

        _state.update { current ->
            current.copy(
                status = status,
                statusMessage = statusMessage,
                logs = if (remoteLogs.isNotEmpty()) remoteLogs else current.logs
            )
        }
        _trafficState.update {
            it.copy(
                bytesUp = b.getLong("bytesUp", 0L),
                bytesDown = b.getLong("bytesDown", 0L),
                speedUpBps = b.getLong("speedUpBps", 0L),
                speedDownBps = b.getLong("speedDownBps", 0L),
                pingMs = if (b.containsKey("pingMs")) b.getInt("pingMs") else null
            )
        }
    }

    private fun applyStatusLocal(status: ConnectionStatus, message: String) {
        if (status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.ERROR) {
            lastSampleTimeMs = 0L
            lastBytesUp = 0L
            lastBytesDown = 0L
            _trafficState.update {
                it.copy(bytesUp = 0L, bytesDown = 0L, speedUpBps = 0L, speedDownBps = 0L)
            }
        }
        _state.update { it.copy(status = status, statusMessage = message) }
    }

    private fun writeDebugFile(level: String, message: String, timestamp: String) {
        val file = debugLogFile ?: return
        logIoExecutor.execute {
            runCatching {
                if (file.exists() && file.length() > MAX_DEBUG_FILE_BYTES) {
                    val backup = File(file.parentFile, "vexotun-debug.log.1")
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }
                file.appendText("[$timestamp] [$level] $message\n")
            }
        }
    }

    fun setStatus(status: ConnectionStatus, message: String) {
        applyStatusLocal(status, message)
        if (isServiceProcess) {
            val bundle = Bundle().apply {
                putInt("statusOrdinal", status.ordinal)
                putString("statusMessage", message)
            }
            broadcast(VpnIpc.MSG_STATUS_CHANGED, bundle)
        }
    }

    fun updateTraffic(bytesUp: Long, bytesDown: Long) {
        val now = SystemClock.elapsedRealtime()
        val prevTime = lastSampleTimeMs
        val (upSpeed, downSpeed) = if (prevTime > 0L && now > prevTime) {
            val dtMs = now - prevTime
            val dUp = (bytesUp - lastBytesUp).coerceAtLeast(0L)
            val dDown = (bytesDown - lastBytesDown).coerceAtLeast(0L)
            (dUp * 1000L / dtMs) to (dDown * 1000L / dtMs)
        } else {
            0L to 0L
        }
        lastSampleTimeMs = now
        lastBytesUp = bytesUp
        lastBytesDown = bytesDown

        val updated = _trafficState.updateAndGet {
            it.copy(
                bytesUp = bytesUp,
                bytesDown = bytesDown,
                speedUpBps = upSpeed,
                speedDownBps = downSpeed
            )
        }
        if (isServiceProcess && registeredClients.isNotEmpty()) {
            val bundle = Bundle().apply {
                putLong("bytesUp", updated.bytesUp)
                putLong("bytesDown", updated.bytesDown)
                putLong("speedUpBps", updated.speedUpBps)
                putLong("speedDownBps", updated.speedDownBps)
                if (updated.pingMs != null) putInt("pingMs", updated.pingMs)
            }
            broadcast(VpnIpc.MSG_TRAFFIC_UPDATED, bundle)
        }
    }

    fun setPing(pingMs: Int?) {
        val updated = _trafficState.updateAndGet { it.copy(pingMs = pingMs) }
        if (isServiceProcess && registeredClients.isNotEmpty()) {
            val bundle = Bundle().apply {
                putLong("bytesUp", updated.bytesUp)
                putLong("bytesDown", updated.bytesDown)
                putLong("speedUpBps", updated.speedUpBps)
                putLong("speedDownBps", updated.speedDownBps)
                if (updated.pingMs != null) putInt("pingMs", updated.pingMs)
            }
            broadcast(VpnIpc.MSG_TRAFFIC_UPDATED, bundle)
        }
    }

    fun appendLog(level: String, message: String) {
        val norm = level.uppercase(Locale.ROOT)
        if (!isServiceProcess) {
            val bundle = Bundle().apply {
                putString("level", norm)
                putString("message", message)
            }
            if (sendToService(VpnIpc.MSG_APPEND_LOG, data = bundle)) {
                return
            }
        }
        when (norm) {
            "ERROR" -> android.util.Log.e("Vexotun", message)
            "WARN" -> android.util.Log.w("Vexotun", message)
            "DEBUG" -> android.util.Log.d("Vexotun", message)
            else -> android.util.Log.i("Vexotun", message)
        }
        val ts = currentLogTime()
        writeDebugFile(norm, message, ts)
        if (norm == "DEBUG") return

        val entry = LogEntry(id = nextLogId.getAndIncrement(), level = norm, message = message, timestamp = ts)
        appendLocalLogEntry(entry)
        if (isServiceProcess) {
            val bundle = Bundle().apply {
                putLong("id", entry.id)
                putString("level", entry.level)
                putString("message", entry.message)
                putString("timestamp", entry.timestamp)
            }
            broadcast(VpnIpc.MSG_LOG_APPENDED, bundle)
        }
    }

    private fun appendLocalLogEntry(entry: LogEntry) {
        _state.update { current ->
            val list = current.logs
            val updated = if (list.size >= MAX_LOGS) {
                ArrayList<LogEntry>(MAX_LOGS).apply {
                    addAll(list.subList(list.size - (MAX_LOGS - 1), list.size))
                    add(entry)
                }
            } else {
                ArrayList<LogEntry>(list.size + 1).apply {
                    addAll(list)
                    add(entry)
                }
            }
            current.copy(logs = updated)
        }
    }

    fun readDebugLog(): String {
        val file = debugLogFile ?: return ""
        return runCatching {
            if (!file.exists()) "" else file.readText()
        }.getOrElse { "" }
    }

    fun ensureInitialLogs(messages: List<String>, debugMessages: List<String> = emptyList()) {
        val time = currentLogTime()
        debugMessages.forEach { writeDebugFile("DEBUG", it, time) }
        _state.update { current ->
            if (current.logs.isNotEmpty()) return@update current
            messages.forEach { writeDebugFile("INFO", it, time) }
            current.copy(
                logs = messages.map { msg ->
                    LogEntry(id = nextLogId.getAndIncrement(), level = "INFO", message = msg, timestamp = time)
                }
            )
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
        if (isServiceProcess) {
            broadcast(VpnIpc.MSG_LOGS_CLEARED)
        } else {
            sendToService(VpnIpc.MSG_CLEAR_LOGS)
        }
    }
}
