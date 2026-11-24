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
    WSS("wss", "WebSocket TLS")
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
    val wsHost: String = ""
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
    val allowInsecure: Boolean = false
)

@Serializable
data class TunnelProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Profil Baru",
    val mode: TunnelMode = TunnelMode.XRAY,
    val ssh: SshProfile = SshProfile(),
    val xray: XrayProfile = XrayProfile()
) {
    fun summary(): String = when (mode) {
        TunnelMode.SSH -> {
            val h = ssh.host.ifBlank { "Host belum diisi" }
            "${ssh.transport.label} • $h:${ssh.port}"
        }
        TunnelMode.XRAY -> {
            val h = xray.address.ifBlank { "Host belum diisi" }
            val tlsTag = if (xray.tls) "TLS" else "Non-TLS"
            "${xray.protocol.label} • ${xray.network.label} $tlsTag • $h:${xray.port}"
        }
    }
}

@Serializable
data class VexotunConfig(
    val profiles: List<TunnelProfile> = emptyList(),
    val activeProfileId: String = "",
    val mtu: Int = 1420,
    val socksPort: Int = 10808,
    val dnsServer: String = "8.8.8.8",
    val splitMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val splitPackages: List<String> = emptyList(),
    val darkTheme: Boolean = true
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
    val wsHost: String
)

@Serializable
data class CoreTunnelConfig(
    val mode: String,
    val mtu: Int,
    val socksPort: Int,
    val ssh: CoreSshPayload,
    val xrayConfigJson: String
)

@Serializable
data class CoreStats(
    val running: Boolean = false,
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L
)
