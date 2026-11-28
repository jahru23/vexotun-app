package com.vexotun.app.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object LinkParser {

    private val schemeStartRegex = Regex("""(vmess|vless|trojan|ss)://""", RegexOption.IGNORE_CASE)
    private val json = Json { ignoreUnknownKeys = true }

    fun parseShareLinks(rawInput: String): Result<List<TunnelProfile>> {
        val candidates = extractLinks(rawInput)
        if (candidates.isEmpty()) {
            return Result.failure(IllegalArgumentException("Link tidak ditemukan. Gunakan format vmess://, vless://, trojan://, atau ss://"))
        }
        val parsed = mutableListOf<TunnelProfile>()
        var lastError: Throwable? = null
        for (link in candidates) {
            parseSingleLink(link)
                .onSuccess { parsed.add(it) }
                .onFailure { lastError = it }
        }
        return if (parsed.isNotEmpty()) {
            Result.success(parsed)
        } else {
            Result.failure(lastError ?: IllegalArgumentException("Semua link tidak valid"))
        }
    }

    fun parseShareLink(rawInput: String): Result<TunnelProfile> {
        val first = extractLinks(rawInput).firstOrNull()
            ?: return Result.failure(IllegalArgumentException("Link tidak ditemukan. Gunakan format vmess://, vless://, trojan://, atau ss://"))
        return parseSingleLink(first)
    }

    private fun extractLinks(rawInput: String): List<String> {
        val out = mutableListOf<String>()
        rawInput.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach
            val starts = schemeStartRegex.findAll(line).map { it.range.first }.toList()
            if (starts.isEmpty()) return@forEach
            for (i in starts.indices) {
                val start = starts[i]
                val end = if (i + 1 < starts.size) starts[i + 1] else line.length
                val slice = line.substring(start, end).trim()
                if (slice.isNotBlank()) out.add(slice)
            }
        }
        return out
    }

    private fun parseSingleLink(link: String): Result<TunnelProfile> = runCatching {
        when {
            link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link.substring(8))
            link.startsWith("vless://", ignoreCase = true) -> parseUriBased(link, XrayProtocol.VLESS)
            link.startsWith("trojan://", ignoreCase = true) -> parseUriBased(link, XrayProtocol.TROJAN)
            link.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(link.substring(5))
            else -> error("Protokol tidak didukung")
        }
    }

    private fun parseVmess(rawBody: String): TunnelProfile {
        val cleaned = rawBody.substringBefore('#').trim()
        val hashRemark = rawBody.indexOf('#').let { idx ->
            if (idx >= 0) decodeUrl(rawBody.substring(idx + 1)).trim() else ""
        }
        val decoded = decodeBase64Flexible(cleaned)
        val obj = json.parseToJsonElement(decoded).jsonObject

        val remark = obj.str("ps").ifBlank { hashRemark }.ifBlank { obj.str("add") }.ifBlank { "VMess" }
        val address = obj.str("add")
        val port = obj.int("port", 443)
        val uuid = obj.str("id")
        val aid = obj.int("aid", 0)
        val net = mapTransport(obj.str("net"))
        val headerType = if (net == XrayTransport.TCP && obj.str("type").equals("http", true)) "http" else ""
        val xhttpMode = if (net == XrayTransport.XHTTP) obj.str("mode") else ""
        val grpcMulti = net == XrayTransport.GRPC && obj.str("type").equals("multi", true)
        val tlsRaw = obj.str("tls")
        val secMode = when {
            tlsRaw.equals("reality", true) -> XraySecurityMode.REALITY
            tlsRaw.equals("tls", true) -> XraySecurityMode.TLS
            else -> XraySecurityMode.NONE
        }
        val host = obj.str("host")
        val sni = obj.str("sni").ifBlank { host }
        val path = obj.str("path")
        val security = obj.str("scy").ifBlank { "auto" }
        val fp = obj.str("fp")
        val alpn = obj.str("alpn")
        val allowInsecure = obj.str("allowInsecure") == "1" || obj.str("allowInsecure").equals("true", true)

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
                tls = secMode == XraySecurityMode.TLS,
                securityMode = secMode,
                sni = sni,
                host = host,
                path = path,
                headerType = headerType,
                grpcMultiMode = grpcMulti,
                xhttpMode = xhttpMode,
                fingerprint = fp,
                alpn = alpn,
                allowInsecure = allowInsecure,
                muxEnabled = false
            )
        )
    }

    private fun parseUriBased(link: String, protocol: XrayProtocol): TunnelProfile {
        val afterScheme = link.substringAfter("://")
        val hashIdx = afterScheme.indexOf('#')
        val remarkRaw = if (hashIdx >= 0) afterScheme.substring(hashIdx + 1) else ""
        val beforeHash = if (hashIdx >= 0) afterScheme.substring(0, hashIdx) else afterScheme

        val queryIdx = beforeHash.indexOf('?')
        val authorityPart = if (queryIdx >= 0) beforeHash.substring(0, queryIdx) else beforeHash
        val rawQuery = if (queryIdx >= 0) beforeHash.substring(queryIdx + 1) else ""

        val atIdx = authorityPart.lastIndexOf('@')
        require(atIdx > 0) { "UUID / Password kosong" }
        val userId = decodeUrl(authorityPart.substring(0, atIdx)).trim()
        val hostPort = authorityPart.substring(atIdx + 1).trim()
        val (address, port) = splitHostPort(hostPort, 443)
        require(address.isNotBlank() && userId.isNotBlank()) { "Host atau kredensial kosong" }

        val query = parseQueryParams(rawQuery)
        val remark = decodeUrl(remarkRaw).trim().ifBlank { address }

        val net = mapTransport(query["type"] ?: "tcp")
        val defaultSecurity = if (protocol == XrayProtocol.TROJAN) "tls" else "none"
        val security = query["security"].orEmpty().ifBlank { defaultSecurity }.lowercase()
        val secMode = when (security) {
            "reality" -> XraySecurityMode.REALITY
            "tls", "xtls" -> XraySecurityMode.TLS
            else -> XraySecurityMode.NONE
        }
        val sni = query["sni"] ?: query["peer"] ?: ""
        val host = query["host"] ?: query["authority"] ?: ""
        val path = when (net) {
            XrayTransport.GRPC -> query["serviceName"] ?: query["path"] ?: ""
            else -> query["path"] ?: ""
        }
        val headerType = if (net == XrayTransport.TCP) query["headerType"] ?: "" else ""
        val grpcMultiMode = net == XrayTransport.GRPC && query["mode"].equals("multi", true)
        val xhttpMode = if (net == XrayTransport.XHTTP) query["mode"] ?: "" else ""
        val flow = query["flow"] ?: ""
        val fp = query["fp"] ?: ""
        val alpn = query["alpn"] ?: ""
        val vcn = query["vcn"] ?: query["verifyPeerCertByName"] ?: ""
        val pcs = query["pcs"] ?: query["pinnedPeerCertSha256"] ?: ""
        val pbk = query["pbk"] ?: ""
        val sid = query["sid"] ?: ""
        val spx = query["spx"]?.ifBlank { "/" } ?: "/"
        val scy = query["encryption"] ?: "auto"
        val allowInsecure = query["allowInsecure"] == "1" || query["allowInsecure"].equals("true", true)

        return TunnelProfile(
            name = remark,
            mode = TunnelMode.XRAY,
            xray = XrayProfile(
                protocol = protocol,
                address = address,
                port = port,
                id = userId,
                security = scy,
                flow = flow,
                network = net,
                tls = secMode == XraySecurityMode.TLS,
                securityMode = secMode,
                sni = sni,
                host = host,
                path = path,
                headerType = headerType,
                grpcMultiMode = grpcMultiMode,
                xhttpMode = xhttpMode,
                fingerprint = fp,
                alpn = alpn,
                verifyPeerCertByName = vcn,
                pinnedPeerCertSha256 = pcs,
                realityPublicKey = pbk,
                realityShortId = sid,
                realitySpiderX = spx,
                allowInsecure = allowInsecure,
                muxEnabled = false
            )
        )
    }

    private fun parseShadowsocks(rawBody: String): TunnelProfile {
        val hashIdx = rawBody.indexOf('#')
        val remark = if (hashIdx >= 0) decodeUrl(rawBody.substring(hashIdx + 1)).trim() else "Shadowsocks"
        val mainPart = if (hashIdx >= 0) rawBody.substring(0, hashIdx) else rawBody
        val queryPart = mainPart.substringBefore('?')

        val decodedPart = if ('@' in queryPart) {
            val splitAt = queryPart.lastIndexOf('@')
            val userEnc = queryPart.substring(0, splitAt)
            val hostPort = queryPart.substring(splitAt + 1)
            val creds = if (':' in userEnc) userEnc else decodeBase64Flexible(userEnc)
            "$creds@$hostPort"
        } else {
            decodeBase64Flexible(queryPart)
        }

        val atIdx = decodedPart.lastIndexOf('@')
        require(atIdx > 0) { "Format Shadowsocks tidak valid" }
        val creds = decodedPart.substring(0, atIdx)
        val hostPort = decodedPart.substring(atIdx + 1)
        val colonIdx = creds.indexOf(':')
        require(colonIdx > 0) { "Method/password Shadowsocks tidak valid" }
        val method = creds.substring(0, colonIdx)
        val password = creds.substring(colonIdx + 1)
        val (address, port) = splitHostPort(hostPort, 8388)

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
                tls = false,
                securityMode = XraySecurityMode.NONE,
                muxEnabled = false
            )
        )
    }

    private fun splitHostPort(rawHostPort: String, defaultPort: Int): Pair<String, Int> {
        val trimmed = rawHostPort.trim().trimEnd('/')
        if (trimmed.startsWith("[")) {
            val endBracket = trimmed.indexOf(']')
            if (endBracket > 0) {
                val host = trimmed.substring(1, endBracket)
                val rest = trimmed.substring(endBracket + 1)
                val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: defaultPort else defaultPort
                return host to port
            }
        }
        val colonIdx = trimmed.lastIndexOf(':')
        return if (colonIdx > 0 && trimmed.count { it == ':' } == 1) {
            val host = trimmed.substring(0, colonIdx).trim()
            val port = trimmed.substring(colonIdx + 1).toIntOrNull() ?: defaultPort
            host to port
        } else {
            trimmed to defaultPort
        }
    }

    private fun mapTransport(raw: String): XrayTransport = when (raw.lowercase()) {
        "ws", "websocket" -> XrayTransport.WS
        "grpc" -> XrayTransport.GRPC
        "httpupgrade" -> XrayTransport.HTTPUPGRADE
        "xhttp", "splithttp" -> XrayTransport.XHTTP
        else -> XrayTransport.TCP
    }

    private fun decodeBase64Flexible(input: String): String {
        val normalized = input.filterNot(Char::isWhitespace)
            .replace('-', '+')
            .replace('_', '/')
        val bytes = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(normalized)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else decodeUrl(pair.substring(0, idx)) to decodeUrl(pair.substring(idx + 1))
        }.toMap()
    }

    private fun decodeUrl(value: String): String {
        val safePercent = value.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25")
        return runCatching { URLDecoder.decode(safePercent.replace("+", "%2B"), "UTF-8") }
            .recoverCatching { URLDecoder.decode(safePercent, "UTF-8") }
            .getOrDefault(value)
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.int(key: String, default: Int): Int =
        this[key]?.jsonPrimitive?.intOrNull
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: default
}
