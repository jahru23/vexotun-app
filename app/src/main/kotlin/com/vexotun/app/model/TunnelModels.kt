package com.vexotun.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class TunnelMode(val label: String) {
    SSH("SSH"),
    XRAY("Xray")
}

@Serializable
enum class SshTransport(val wireValue: String, val label: String) {
    DIRECT("direct", "Direct"),
    HTTP_PROXY("http_proxy", "HTTP Proxy"),
    SSL("ssl", "SSL / TLS"),
    WS("ws", "WebSocket"),
    WSS("wss", "WebSocket TLS"),
    SLOWDNS("slowdns", "SlowDNS")
}

@Serializable
enum class SlowDnsRecordType(val wireValue: String, val label: String) {
    TXT("TXT", "TXT"),
    NULL("NULL", "NULL"),
    CNAME("CNAME", "CNAME"),
    NS("NS", "NS"),
    A("A", "A"),
    MX("MX", "MX")
}

@Serializable
enum class XrayProtocol(val wireValue: String, val label: String) {
    VMESS("vmess", "VMess"),
    VLESS("vless", "VLESS"),
    TROJAN("trojan", "Trojan"),
    SHADOWSOCKS("shadowsocks", "Shadowsocks")
}

@Serializable
enum class XrayTransport(val wireValue: String, val label: String) {
    WS("ws", "WebSocket"),
    GRPC("grpc", "gRPC"),
    HTTPUPGRADE("httpupgrade", "HTTPUpgrade"),
    TCP("tcp", "TCP")
}

@Serializable
enum class SplitTunnelMode {
    ALL_APPS,
    INCLUDE_ONLY,
    EXCLUDE_SELECTED
}

@Serializable
data class SshProfile(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val transport: SshTransport = SshTransport.HTTP_PROXY,
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val sni: String = "",
    val payload: String = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
    val wsPath: String = "/",
    val wsHost: String = "",
    val dnsServer: String = "8.8.8.8",
    val dnsType: SlowDnsRecordType = SlowDnsRecordType.TXT,
    val nameserver: String = "",
    val publicKey: String = ""
)

@Serializable
data class XrayProfile(
    val protocol: XrayProtocol = XrayProtocol.VMESS,
    val address: String = "",
    val port: Int = 443,
    val id: String = "",
    val alterId: Int = 0,
    val security: String = "auto",
    val flow: String = "",
    val network: XrayTransport = XrayTransport.WS,
    val tls: Boolean = true,
    val sni: String = "",
    val host: String = "",
    val path: String = "",
    val allowInsecure: Boolean = false,
    val useCustomPayload: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val payload: String = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
    val muxEnabled: Boolean = true
)

@Serializable
data class VxtLockInfo(
    val locked: Boolean = false,
    val hideHost: Boolean = false,
    val hideSni: Boolean = false,
    val hideAuth: Boolean = false,
    val hidePayload: Boolean = false,
    val author: String = "",
    val note: String = "",
    val expiresAtMs: Long = 0L
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtMs > 0L && nowMs > expiresAtMs
}

@Serializable
data class TunnelProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Profil Baru",
    val mode: TunnelMode = TunnelMode.XRAY,
    val ssh: SshProfile = SshProfile(),
    val xray: XrayProfile = XrayProfile(),
    val lockInfo: VxtLockInfo? = null
) {
    val isLocked: Boolean get() = lockInfo?.locked == true

    fun summary(): String = when (mode) {
        TunnelMode.SSH -> {
            val hostPart = when {
                lockInfo?.hideHost == true -> "Server Terkunci"
                ssh.transport == SshTransport.SLOWDNS -> {
                    val h = ssh.host.ifBlank { ssh.nameserver.ifBlank { "NS belum diisi" } }
                    if (ssh.host.isNotBlank()) "$h:${ssh.port} (${ssh.dnsType.label})" else "$h (${ssh.dnsType.label})"
                }
                else -> "${ssh.host.ifBlank { "Host belum diisi" }}:${ssh.port}"
            }
            "${ssh.transport.label} • $hostPart"
        }
        TunnelMode.XRAY -> {
            val tlsTag = if (xray.tls) "TLS" else "Non-TLS"
            val hostPart = if (lockInfo?.hideHost == true) {
                "Server Terkunci"
            } else {
                val h = xray.address.ifBlank { "Host belum diisi" }
                "$h:${xray.port}"
            }
            "${xray.protocol.label} • ${xray.network.label} $tlsTag • $hostPart"
        }
    }
}

@Serializable
data class VexotunConfig(
    val profiles: List<TunnelProfile> = emptyList(),
    val activeProfileId: String = "",
    val mtu: Int = 1420,
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val dnsServer: String = "8.8.8.8",
    val splitMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val splitPackages: List<String> = emptyList(),
    val darkTheme: Boolean = true,
    val autoReconnect: Boolean = true,
    val bypassLan: Boolean = true,
    val wakeLock: Boolean = false,
    val allowLan: Boolean = false
) {
    fun activeProfile(): TunnelProfile? =
        profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
}

@Serializable
data class CoreSshPayload(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val privateKey: String,
    val transport: String,
    val proxyHost: String,
    val proxyPort: Int,
    val sni: String,
    val payload: String,
    val wsPath: String,
    val wsHost: String,
    val dnsServer: String = "8.8.8.8",
    val dnsType: String = "TXT",
    val nameserver: String = "",
    val publicKey: String = ""
)

@Serializable
data class CoreXrayPayload(
    val enabled: Boolean = false,
    val targetHost: String = "",
    val targetPort: Int = 443,
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val payload: String = "",
    val sni: String = ""
)

@Serializable
data class CoreTunnelConfig(
    val mode: String,
    val mtu: Int,
    val socksPort: Int,
    val httpPort: Int = 10809,
    val allowLan: Boolean = false,
    val ssh: CoreSshPayload,
    val xrayConfigJson: String,
    val xrayPayload: CoreXrayPayload = CoreXrayPayload()
)

@Serializable
data class CoreStats(
    val running: Boolean = false,
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L
)
