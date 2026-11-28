package com.vexotun.app.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object XrayConfigBuilder {

    fun buildJson(
        profile: XrayProfile,
        dnsServer: String = "8.8.8.8",
        bypassLan: Boolean = true,
        muxEnabled: Boolean = false,
        useDohDns: Boolean = false
    ): String {
        val outbound = buildOutbound(profile, muxEnabled)
        val effectiveDns = dnsServer.trim().ifBlank { "8.8.8.8" }
        val root = buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "warning") })
            put("dns", buildJsonObject {
                put("queryStrategy", "UseIPv4")
                put("servers", buildJsonArray {
                    if (useDohDns) {
                        add(JsonPrimitive("https://1.1.1.1/dns-query"))
                        add(JsonPrimitive("https://dns.google/dns-query"))
                    } else {
                        add(JsonPrimitive(effectiveDns))
                        if (effectiveDns != "1.1.1.1") add(JsonPrimitive("1.1.1.1"))
                    }
                })
            })
            put("outbounds", buildJsonArray {
                add(outbound)
                add(buildJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    put("settings", buildJsonObject {
                        put("domainStrategy", "AsIs")
                    })
                })
            })
            if (bypassLan) {
                put("routing", buildJsonObject {
                    put("domainStrategy", "IPIfNonMatch")
                    put("rules", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "field")
                            put("ip", buildJsonArray {
                                add(JsonPrimitive("10.0.0.0/8"))
                                add(JsonPrimitive("172.16.0.0/12"))
                                add(JsonPrimitive("192.168.0.0/16"))
                                add(JsonPrimitive("127.0.0.0/8"))
                                add(JsonPrimitive("169.254.0.0/16"))
                            })
                            put("outboundTag", "direct")
                        })
                    })
                })
            }
        }
        return root.toString()
    }

    private fun buildOutbound(p: XrayProfile, muxEnabled: Boolean): JsonObject {
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
        val secMode = p.effectiveSecurityMode()
        val effectiveFp = p.fingerprint.trim().ifBlank { "chrome" }

        val streamSettings = buildJsonObject {
            put("network", p.network.wireValue)
            put("security", secMode.wireValue)

            when (secMode) {
                XraySecurityMode.TLS -> put("tlsSettings", buildJsonObject {
                    put("serverName", effectiveSni)
                    put("fingerprint", effectiveFp)
                    val alpnList = p.alpn.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .ifEmpty {
                            when (p.network) {
                                XrayTransport.GRPC -> listOf("h2")
                                XrayTransport.WS, XrayTransport.HTTPUPGRADE -> listOf("http/1.1")
                                XrayTransport.XHTTP, XrayTransport.TCP -> listOf("h2", "http/1.1")
                            }
                        }
                    put("alpn", buildJsonArray {
                        alpnList.forEach { add(JsonPrimitive(it)) }
                    })
                    if (p.verifyPeerCertByName.isNotBlank()) {
                        put("verifyPeerCertByName", p.verifyPeerCertByName.trim())
                    }
                    if (p.pinnedPeerCertSha256.isNotBlank()) {
                        put("pinnedPeerCertSha256", p.pinnedPeerCertSha256.trim())
                    }
                })

                XraySecurityMode.REALITY -> put("realitySettings", buildJsonObject {
                    put("show", false)
                    put("fingerprint", if (effectiveFp == "unsafe" || effectiveFp == "hellogolang") "chrome" else effectiveFp)
                    put("serverName", effectiveSni)
                    put("publicKey", p.realityPublicKey.trim().trimEnd('='))
                    put("shortId", p.realityShortId.trim())
                    put("spiderX", p.realitySpiderX.trim().let { if (it.startsWith("/")) it else "/$it" })
                })

                XraySecurityMode.NONE -> Unit
            }

            when (p.network) {
                XrayTransport.WS -> put("wsSettings", buildJsonObject {
                    put("path", p.path.trim().ifBlank { "/" })
                    if (effectiveHost.isNotBlank()) {
                        put("host", effectiveHost)
                    }
                })

                XrayTransport.GRPC -> put("grpcSettings", buildJsonObject {
                    put("serviceName", p.path.trim())
                    if (p.grpcMultiMode) {
                        put("multiMode", true)
                    }
                    if (p.host.isNotBlank()) {
                        put("authority", p.host.trim())
                    }
                })

                XrayTransport.HTTPUPGRADE -> put("httpupgradeSettings", buildJsonObject {
                    put("path", p.path.trim().ifBlank { "/" })
                    if (effectiveHost.isNotBlank()) {
                        put("host", effectiveHost)
                    }
                })

                XrayTransport.XHTTP -> put("xhttpSettings", buildJsonObject {
                    put("path", p.path.trim().ifBlank { "/" })
                    if (effectiveHost.isNotBlank()) {
                        put("host", effectiveHost)
                    }
                    if (p.xhttpMode.isNotBlank()) {
                        put("mode", p.xhttpMode.trim())
                    }
                })

                XrayTransport.TCP -> {
                    if (p.headerType.equals("http", true)) {
                        put("tcpSettings", buildJsonObject {
                            put("header", buildJsonObject {
                                put("type", "http")
                                put("request", buildJsonObject {
                                    put("path", buildJsonArray {
                                        add(JsonPrimitive(p.path.trim().ifBlank { "/" }))
                                    })
                                    if (effectiveHost.isNotBlank()) {
                                        put("headers", buildJsonObject {
                                            put("Host", buildJsonArray {
                                                add(JsonPrimitive(effectiveHost))
                                            })
                                        })
                                    }
                                })
                            })
                        })
                    }
                }
            }

            put("sockopt", buildJsonObject {
                put("tcpNoDelay", true)
                put("tcpKeepAliveInterval", 30)
            })
        }

        val useMux = muxEnabled &&
            p.muxEnabled &&
            p.protocol != XrayProtocol.SHADOWSOCKS &&
            p.flow.isBlank() &&
            p.network != XrayTransport.GRPC &&
            p.network != XrayTransport.XHTTP
        return buildJsonObject {
            put("tag", "proxy")
            put("protocol", p.protocol.wireValue)
            put("settings", settings)
            put("streamSettings", streamSettings)
            if (useMux) {
                put("mux", buildJsonObject {
                    put("enabled", true)
                    put("concurrency", 8)
                    put("xudpConcurrency", 8)
                    put("xudpProxyUDP443", "allow")
                })
            }
        }
    }
}
