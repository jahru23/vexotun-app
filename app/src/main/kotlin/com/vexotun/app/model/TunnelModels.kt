package com.vexotun.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class TunnelMode {
    SSH,
    XRAY
}

@Serializable
enum class SshTransport(val wireValue: String, val label: String) {
    DIRECT("direct", "Direct SSH"),
    HTTP_PROXY("http_proxy", "HTTP Proxy + Payload"),
    SSL("ssl", "SSL / TLS (SNI)"),
    WS("ws", "WebSocket (WS)"),
    WSS("wss", "WebSocket TLS (WSS)")
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
data class VexotunConfig(
    val mode: TunnelMode = TunnelMode.SSH,
    val mtu: Int = 1500,
    val socksPort: Int = 10808,
    val dnsServer: String = "8.8.8.8",
    val ssh: SshProfile = SshProfile(),
    val xrayConfigJson: String = DEFAULT_XRAY_TEMPLATE,
    val splitMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val splitPackages: List<String> = emptyList()
)

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

const val DEFAULT_XRAY_TEMPLATE = """{
  "log": {
    "loglevel": "warning"
  },
  "outbounds": [
    {
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "example.com",
            "port": 443,
            "users": [
              {
                "id": "00000000-0000-0000-0000-000000000000",
                "encryption": "none"
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "ws",
        "security": "tls",
        "tlsSettings": {
          "serverName": "example.com",
          "allowInsecure": false
        },
        "wsSettings": {
          "path": "/vless"
        }
      }
    }
  ]
}"""
