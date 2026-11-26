package com.vexotun.app.vpn

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

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
    private val logIoExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var debugLogFile: File? = null

    fun init(context: Context) {
        if (debugLogFile != null) return
        val dir = File(context.applicationContext.filesDir, "logs")
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            debugLogFile = File(dir, "vexotun-debug.log")
        }
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

    fun setStatus(status: ConnectionStatus, message: String) {
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
        _trafficState.update {
            it.copy(
                bytesUp = bytesUp,
                bytesDown = bytesDown,
                speedUpBps = upSpeed,
                speedDownBps = downSpeed
            )
        }
    }

    fun setPing(pingMs: Int?) {
        _trafficState.update { it.copy(pingMs = pingMs) }
    }

    fun appendLog(level: String, message: String) {
        val norm = level.uppercase(Locale.ROOT)
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
    }
}
