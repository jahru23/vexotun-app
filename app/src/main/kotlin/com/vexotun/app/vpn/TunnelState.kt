package com.vexotun.app.vpn

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private const val MAX_LOGS = 250
    private val nextLogId = AtomicLong(1L)

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
        val entry = LogEntry(id = nextLogId.getAndIncrement(), level = level, message = message)
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

    fun ensureInitialLogs(messages: List<String>) {
        _state.update { current ->
            if (current.logs.isNotEmpty()) return@update current
            val time = currentLogTime()
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
