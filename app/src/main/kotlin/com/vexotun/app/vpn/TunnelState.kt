package com.vexotun.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class LogEntry(
    val level: String,
    val message: String
)

data class VpnRuntimeState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val statusMessage: String = "Disconnected",
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L,
    val pingMs: Int? = null,
    val logs: List<LogEntry> = emptyList()
)

object TunnelState {
    private const val MAX_LOGS = 300

    private val _state = MutableStateFlow(VpnRuntimeState())
    val state: StateFlow<VpnRuntimeState> = _state.asStateFlow()

    fun setStatus(status: ConnectionStatus, message: String) {
        _state.update { it.copy(status = status, statusMessage = message) }
    }

    fun updateTraffic(bytesUp: Long, bytesDown: Long) {
        _state.update { it.copy(bytesUp = bytesUp, bytesDown = bytesDown) }
    }

    fun setPing(pingMs: Int?) {
        _state.update { it.copy(pingMs = pingMs) }
    }

    fun appendLog(level: String, message: String) {
        _state.update { current ->
            val updated = (current.logs + LogEntry(level, message)).takeLast(MAX_LOGS)
            current.copy(logs = updated)
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }
}
