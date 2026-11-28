package com.vexotun.app.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object LinkExporter {

    fun exportShareLink(profile: TunnelProfile): Result<String> = runCatching {
        require(!profile.isLocked) { "Config terkunci tidak dapat dibagikan sebagai link" }
        require(profile.mode == TunnelMode.XRAY) { "Share link hanya tersedia untuk config Xray. Gunakan Export .vxt untuk SSH." }

        val x = profile.xray
        require(x.address.isNotBlank() && x.id.isNotBlank()) { "Data host atau UUID/Password belum lengkap" }

        when (x.protocol) {
            XrayProtocol.VMESS -> exportVmess(profile.name, x)
            XrayProtocol.VLESS -> exportUriBased("vless", profile.name, x)
            XrayProtocol.TROJAN -> exportUriBased("trojan", profile.name, x)
            XrayProtocol.SHADOWSOCKS -> exportShadowsocks(profile.name, x)
        }
    }

    private fun exportVmess(name: String, x: XrayProfile): String {
        val typeField = when {
            x.network == XrayTransport.TCP && x.headerType.equals("http", true) -> "http"
            x.network == XrayTransport.GRPC && x.grpcMultiMode -> "multi"
            else -> "none"
        }
        val tlsValue = when (x.effectiveSecurityMode()) {
            XraySecurityMode.REALITY -> "reality"
            XraySecurityMode.TLS -> "tls"
            XraySecurityMode.NONE -> ""
        }
        val obj = buildJsonObject {
            put("v", "2")
            put("ps", name)
            put("add", x.address.trim())
            put("port", x.port.toString())
            put("id", x.id.trim())
            put("aid", x.alterId.toString())
            put("scy", x.security.ifBlank { "auto" })
            put("net", x.network.wireValue)
            put("type", typeField)
            put("host", x.host.trim())
            put("path", x.path.trim())
            put("tls", tlsValue)
            put("sni", x.sni.trim())
            if (x.fingerprint.isNotBlank()) put("fp", x.fingerprint.trim())
            if (x.alpn.isNotBlank()) put("alpn", x.alpn.trim())
            if (x.network == XrayTransport.XHTTP && x.xhttpMode.isNotBlank()) put("mode", x.xhttpMode.trim())
            if (x.allowInsecure) put("allowInsecure", "1")
        }
        val b64 = Base64.Default.encode(obj.toString().toByteArray(Charsets.UTF_8))
        return "vmess://$b64"
    }

    private fun exportUriBased(scheme: String, name: String, x: XrayProfile): String {
        val secMode = x.effectiveSecurityMode()
        val params = linkedMapOf(
            "type" to x.network.wireValue,
            "security" to secMode.wireValue
        )
        if (x.protocol == XrayProtocol.VLESS) {
            params["encryption"] = "none"
            if (x.flow.isNotBlank()) params["flow"] = x.flow.trim()
        }
        if (x.network == XrayTransport.TCP && x.headerType.equals("http", true)) {
            params["headerType"] = "http"
        }
        if (x.network == XrayTransport.GRPC && x.grpcMultiMode) {
            params["mode"] = "multi"
        }
        if (x.network == XrayTransport.XHTTP && x.xhttpMode.isNotBlank()) {
            params["mode"] = x.xhttpMode.trim()
        }
        if (x.host.isNotBlank()) params["host"] = x.host.trim()
        if (x.sni.isNotBlank()) params["sni"] = x.sni.trim()
        if (x.path.isNotBlank()) {
            if (x.network == XrayTransport.GRPC) {
                params["serviceName"] = x.path.trim()
            } else {
                params["path"] = x.path.trim()
            }
        }
        if (x.fingerprint.isNotBlank()) params["fp"] = x.fingerprint.trim()
        if (x.alpn.isNotBlank()) params["alpn"] = x.alpn.trim()
        if (secMode == XraySecurityMode.TLS) {
            if (x.verifyPeerCertByName.isNotBlank()) params["vcn"] = x.verifyPeerCertByName.trim()
            if (x.pinnedPeerCertSha256.isNotBlank()) params["pcs"] = x.pinnedPeerCertSha256.trim()
            if (x.allowInsecure) params["allowInsecure"] = "1"
        } else if (secMode == XraySecurityMode.REALITY) {
            if (x.realityPublicKey.isNotBlank()) params["pbk"] = x.realityPublicKey.trim()
            if (x.realityShortId.isNotBlank()) params["sid"] = x.realityShortId.trim()
            if (x.realitySpiderX.isNotBlank()) params["spx"] = x.realitySpiderX.trim()
        }

        val query = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val user = enc(x.id.trim())
        val host = x.address.trim().let { if (it.contains(":") && !it.startsWith("[")) "[$it]" else it }
        val frag = enc(name.ifBlank { x.address.trim() })
        return "$scheme://$user@$host:${x.port}?$query#$frag"
    }

    private fun exportShadowsocks(name: String, x: XrayProfile): String {
        val method = x.security.ifBlank { "aes-128-gcm" }
        val userInfo = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode("$method:${x.id.trim()}".toByteArray(Charsets.UTF_8))
        val host = x.address.trim().let { if (it.contains(":") && !it.startsWith("[")) "[$it]" else it }
        val frag = enc(name.ifBlank { x.address.trim() })
        return "ss://$userInfo@$host:${x.port}#$frag"
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
