package com.vexotun.app.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object XrayConfigBuilder {

    fun buildJson(profile: XrayProfile): String {
        val outbound = buildOutbound(profile)
        val root = buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "warning") })
            put("outbounds", buildJsonArray { add(outbound) })
        }
        return root.toString()
    }

    private fun buildOutbound(p: XrayProfile): JsonObject {
        val settings = when (p.protocol) {
            XrayProtocol.VMESS -> buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", p.address.trim())
                        put("port", p.port)
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("id", p.id.trim())
                                put("alterId", p.alterId)
                                put("security", p.security.ifBlank { "auto" })
                            })
                        })
                    })
                })
            }

            XrayProtocol.VLESS -> buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", p.address.trim())
                        put("port", p.port)
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("id", p.id.trim())
                                put("encryption", "none")
                                if (p.flow.isNotBlank()) put("flow", p.flow.trim())
                            })
                        })
                    })
                })
            }

            XrayProtocol.TROJAN -> buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", p.address.trim())
                        put("port", p.port)
                        put("password", p.id.trim())
                    })
                })
            }

            XrayProtocol.SHADOWSOCKS -> buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", p.address.trim())
                        put("port", p.port)
                        put("method", p.security.ifBlank { "aes-128-gcm" })
                        put("password", p.id.trim())
                    })
                })
            }
        }

        val effectiveHost = p.host.trim().ifBlank { p.address.trim() }
        val effectiveSni = p.sni.trim().ifBlank { effectiveHost }

        val streamSettings = buildJsonObject {
            put("network", p.network.wireValue)
            put("security", if (p.tls) "tls" else "none")

            if (p.tls) {
                put("tlsSettings", buildJsonObject {
                    put("serverName", effectiveSni)
                    put("allowInsecure", p.allowInsecure)
                })
            }

            when (p.network) {
                XrayTransport.WS -> put("wsSettings", buildJsonObject {
                    put("path", p.path.trim().ifBlank { "/" })
                    if (effectiveHost.isNotBlank()) {
                        put("headers", buildJsonObject {
                            put("Host", effectiveHost)
                        })
                    }
                })

                XrayTransport.GRPC -> put("grpcSettings", buildJsonObject {
                    put("serviceName", p.path.trim())
                })

                XrayTransport.HTTPUPGRADE -> put("httpupgradeSettings", buildJsonObject {
                    put("path", p.path.trim().ifBlank { "/" })
                    if (effectiveHost.isNotBlank()) {
                        put("host", effectiveHost)
                    }
                })

                XrayTransport.TCP -> {
                    // Default raw TCP
                }
            }
        }

        return buildJsonObject {
            put("tag", "proxy")
            put("protocol", p.protocol.wireValue)
            put("settings", settings)
            put("streamSettings", streamSettings)
        }
    }
}
