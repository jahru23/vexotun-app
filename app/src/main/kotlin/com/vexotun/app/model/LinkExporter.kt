package com.vexotun.app.model

import android.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

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
        val obj = buildJsonObject {
            put("v", "2")
            put("ps", name)
            put("add", x.address.trim())
            put("port", x.port.toString())
            put("id", x.id.trim())
            put("aid", x.alterId.toString())
            put("scy", x.security.ifBlank { "auto" })
            put("net", x.network.wireValue)
            put("type", "none")
            put("host", x.host.trim())
            put("path", x.path.trim())
            put("tls", if (x.tls) "tls" else "")
            put("sni", x.sni.trim())
        }
        val b64 = Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "vmess://$b64"
    }

    private fun exportUriBased(scheme: String, name: String, x: XrayProfile): String {
        val params = linkedMapOf(
            "type" to x.network.wireValue,
            "security" to if (x.tls) "tls" else "none"
        )
        if (x.protocol == XrayProtocol.VLESS) {
            params["encryption"] = "none"
            if (x.flow.isNotBlank()) params["flow"] = x.flow.trim()
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
        if (x.allowInsecure) params["allowInsecure"] = "1"

        val query = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val user = enc(x.id.trim())
        val host = x.address.trim().let { if (it.contains(":") && !it.startsWith("[")) "[$it]" else it }
        val frag = enc(name.ifBlank { x.address.trim() })
        return "$scheme://$user@$host:${x.port}?$query#$frag"
    }

    private fun exportShadowsocks(name: String, x: XrayProfile): String {
        val method = x.security.ifBlank { "aes-128-gcm" }
        val userInfo = Base64.encodeToString(
            "$method:${x.id.trim()}".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val host = x.address.trim().let { if (it.contains(":") && !it.startsWith("[")) "[$it]" else it }
        val frag = enc(name.ifBlank { x.address.trim() })
        return "ss://$userInfo@$host:${x.port}#$frag"
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
