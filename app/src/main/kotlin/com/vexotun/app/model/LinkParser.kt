package com.vexotun.app.model

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder

object LinkParser {

    private val linkRegex = Regex("""(vmess|vless|trojan|ss)://\S+""", RegexOption.IGNORE_CASE)
    private val json = Json { ignoreUnknownKeys = true }

    fun parseShareLink(rawInput: String): Result<TunnelProfile> {
        val match = linkRegex.find(rawInput.trim())
            ?: return Result.failure(IllegalArgumentException("Link tidak ditemukan. Gunakan format vmess://, vless://, trojan://, atau ss://"))

        val link = match.value.trim()
        return runCatching {
            when {
                link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link.substring(8))
                link.startsWith("vless://", ignoreCase = true) -> parseUriBased(link, XrayProtocol.VLESS)
                link.startsWith("trojan://", ignoreCase = true) -> parseUriBased(link, XrayProtocol.TROJAN)
                link.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(link.substring(5))
                else -> error("Protokol tidak didukung")
            }
        }
    }

    private fun parseVmess(encoded: String): TunnelProfile {
        val decoded = decodeBase64Flexible(encoded.trim())
        val obj = json.parseToJsonElement(decoded).jsonObject

        val remark = obj.str("ps").ifBlank { obj.str("add") }.ifBlank { "VMess" }
        val address = obj.str("add")
        val port = obj.int("port", 443)
        val uuid = obj.str("id")
        val aid = obj.int("aid", 0)
        val net = mapTransport(obj.str("net"))
        val tls = obj.str("tls").equals("tls", ignoreCase = true)
        val sni = obj.str("sni").ifBlank { obj.str("host") }
        val host = obj.str("host")
        val path = obj.str("path")
        val security = obj.str("scy").ifBlank { "auto" }

        require(address.isNotBlank() && uuid.isNotBlank()) { "Data VMess tidak lengkap (address/uuid kosong)" }

        return TunnelProfile(
            name = remark,
            mode = TunnelMode.XRAY,
            xray = XrayProfile(
                protocol = XrayProtocol.VMESS,
                address = address,
                port = port,
                id = uuid,
                alterId = aid,
                security = security,
                network = net,
                tls = tls,
                sni = sni,
                host = host,
                path = path
            )
        )
    }

    private fun parseUriBased(link: String, protocol: XrayProtocol): TunnelProfile {
        val uri = URI(link)
        val address = uri.host ?: error("Host kosong")
        val port = if (uri.port > 0) uri.port else 443
        val userId = uri.userInfo ?: error("UUID / Password kosong")
        val query = parseQueryParams(uri.rawQuery)
        val remark = uri.rawFragment?.let { decodeUrl(it) }?.ifBlank { null } ?: address

        val net = mapTransport(query["type"] ?: "tcp")
        val security = query["security"] ?: "none"
        val tls = security.equals("tls", ignoreCase = true)
        val sni = query["sni"] ?: query["peer"] ?: ""
        val host = query["host"] ?: ""
        val path = when (net) {
            XrayTransport.GRPC -> query["serviceName"] ?: query["path"] ?: ""
            else -> query["path"] ?: ""
        }
        val flow = query["flow"] ?: ""
        val allowInsecure = query["allowInsecure"] == "1" || query["allowInsecure"].equals("true", true)

        return TunnelProfile(
            name = remark,
            mode = TunnelMode.XRAY,
            xray = XrayProfile(
                protocol = protocol,
                address = address,
                port = port,
                id = userId,
                flow = flow,
                network = net,
                tls = tls,
                sni = sni,
                host = host,
                path = path,
                allowInsecure = allowInsecure
            )
        )
    }

    private fun parseShadowsocks(rawBody: String): TunnelProfile {
        val hashIdx = rawBody.indexOf('#')
        val remark = if (hashIdx >= 0) decodeUrl(rawBody.substring(hashIdx + 1)) else "Shadowsocks"
        val mainPart = if (hashIdx >= 0) rawBody.substring(0, hashIdx) else rawBody
        val queryPart = mainPart.substringBefore('?')

        val decodedPart = if ('@' in queryPart) {
            val userEnc = queryPart.substringBefore('@')
            val hostPort = queryPart.substringAfter('@')
            val creds = if (':' in userEnc) userEnc else decodeBase64Flexible(userEnc)
            "$creds@$hostPort"
        } else {
            decodeBase64Flexible(queryPart)
        }

        val creds = decodedPart.substringBefore('@')
        val hostPort = decodedPart.substringAfter('@')
        val method = creds.substringBefore(':')
        val password = creds.substringAfter(':')
        val address = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':').toIntOrNull() ?: 8388

        require(address.isNotBlank() && method.isNotBlank()) { "Format Shadowsocks tidak valid" }

        return TunnelProfile(
            name = remark.ifBlank { address },
            mode = TunnelMode.XRAY,
            xray = XrayProfile(
                protocol = XrayProtocol.SHADOWSOCKS,
                address = address,
                port = port,
                id = password,
                security = method,
                network = XrayTransport.TCP,
                tls = false
            )
        )
    }

    private fun mapTransport(raw: String): XrayTransport = when (raw.lowercase()) {
        "ws", "websocket" -> XrayTransport.WS
        "grpc" -> XrayTransport.GRPC
        "httpupgrade" -> XrayTransport.HTTPUPGRADE
        else -> XrayTransport.TCP
    }

    private fun decodeBase64Flexible(input: String): String {
        val clean = input.replace('-', '+').replace('_', '/')
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else decodeUrl(pair.substring(0, idx)) to decodeUrl(pair.substring(idx + 1))
        }.toMap()
    }

    private fun decodeUrl(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.int(key: String, default: Int): Int =
        this[key]?.jsonPrimitive?.intOrNull
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: default
}
