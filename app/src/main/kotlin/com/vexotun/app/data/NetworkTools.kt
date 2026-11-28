package com.vexotun.app.data

import android.os.SystemClock
import com.vexotun.app.vpn.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class IpInfoResult(
    val ok: Boolean,
    val viaTunnel: Boolean = false,
    val ip: String = "",
    val country: String = "",
    val city: String = "",
    val isp: String = "",
    val asn: String = "",
    val error: String = ""
)

data class HostCheckResult(
    val ok: Boolean,
    val host: String,
    val port: Int,
    val tcpMs: Int = -1,
    val tlsMs: Int = -1,
    val tlsProtocol: String = "",
    val tlsVerified: Boolean = false,
    val certSubject: String = "",
    val statusLine: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val error: String = ""
)

data class DnsRecordItem(
    val name: String,
    val ttl: Int,
    val data: String
)

data class DnsLookupResult(
    val ok: Boolean,
    val domain: String,
    val type: String,
    val records: List<DnsRecordItem> = emptyList(),
    val error: String = ""
)

data class SpeedCheckResult(
    val ok: Boolean,
    val viaTunnel: Boolean = false,
    val bytes: Long = 0L,
    val elapsedMs: Long = 0L,
    val mbps: Double = 0.0,
    val error: String = ""
)

object NetworkTools {

    private fun directGet(targetUrl: String, timeoutMs: Int = 8000): String {
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Vexotun/1.0")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code")
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    suspend fun fetchIpInfo(useTunnel: Boolean, socksPort: Int): IpInfoResult = withContext(Dispatchers.IO) {
        val url = "https://ipwho.is/"
        val raw = if (useTunnel) {
            TunnelState.fetchSocksUrl(socksPort, url, 10000L).getOrElse { err ->
                return@withContext IpInfoResult(ok = false, viaTunnel = true, error = err.message ?: "Gagal memeriksa IP")
            }
        } else {
            runCatching { directGet(url, 8000) }.getOrElse { err ->
                return@withContext IpInfoResult(ok = false, viaTunnel = false, error = err.message ?: "Gagal memeriksa IP")
            }
        }

        runCatching {
            val obj = JSONObject(raw)
            if (!obj.optBoolean("success", true)) {
                return@withContext IpInfoResult(
                    ok = false,
                    viaTunnel = useTunnel,
                    error = obj.optString("message", "Lookup IP gagal")
                )
            }
            val connObj = obj.optJSONObject("connection")
            IpInfoResult(
                ok = true,
                viaTunnel = useTunnel,
                ip = obj.optString("ip"),
                country = obj.optString("country"),
                city = obj.optString("city"),
                isp = connObj?.optString("isp")?.ifBlank { connObj.optString("org") } ?: "",
                asn = connObj?.optInt("asn", 0)?.let { if (it > 0) "AS$it" else "" } ?: ""
            )
        }.getOrElse { err ->
            IpInfoResult(ok = false, viaTunnel = useTunnel, error = err.message ?: "Parse IP gagal")
        }
    }

    suspend fun checkBugHost(
        host: String,
        port: Int,
        useTls: Boolean,
        sni: String
    ): HostCheckResult = withContext(Dispatchers.IO) {
        val targetHost = host.trim()
        val targetPort = port.coerceIn(1, 65535)
        if (targetHost.isBlank()) {
            return@withContext HostCheckResult(ok = false, host = targetHost, port = targetPort, error = "Host kosong")
        }
        val effectiveSni = sni.trim().ifBlank { targetHost }

        runCatching {
            val t0 = SystemClock.elapsedRealtime()
            val tcpSock = Socket()
            tcpSock.soTimeout = 7000
            tcpSock.connect(InetSocketAddress(targetHost, targetPort), 7000)
            val tcpMs = (SystemClock.elapsedRealtime() - t0).toInt()

            var tlsMs = -1
            var tlsProto = ""
            var tlsVerified = false
            var certSubject = ""

            val ioSock: Socket = if (useTls) {
                val t1 = SystemClock.elapsedRealtime()
                val tm = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val ctx = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(tm), SecureRandom())
                }
                val sslSock = ctx.socketFactory.createSocket(tcpSock, effectiveSni, targetPort, true) as SSLSocket
                sslSock.soTimeout = 7000
                val params = sslSock.sslParameters
                params.serverNames = listOf(SNIHostName(effectiveSni))
                sslSock.sslParameters = params
                sslSock.startHandshake()
                tlsMs = (SystemClock.elapsedRealtime() - t1).toInt()
                tlsProto = sslSock.session.protocol ?: ""
                val certs = runCatching { sslSock.session.peerCertificates }.getOrNull()
                val leaf = certs?.firstOrNull() as? X509Certificate
                certSubject = leaf?.subjectX500Principal?.name.orEmpty()
                tlsVerified = runCatching {
                    val defaultTmf = javax.net.ssl.TrustManagerFactory.getInstance(
                        javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
                    ).apply { init(null as java.security.KeyStore?) }
                    val defaultTm = defaultTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                    val x509Chain = certs?.filterIsInstance<X509Certificate>()?.toTypedArray().orEmpty()
                    if (defaultTm != null && x509Chain.isNotEmpty()) {
                        defaultTm.checkServerTrusted(x509Chain, "RSA")
                        javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                            .verify(effectiveSni, sslSock.session)
                    } else {
                        false
                    }
                }.getOrDefault(false)
                sslSock
            } else {
                tcpSock
            }

            ioSock.use { sock ->
                val req = "HEAD / HTTP/1.1\r\n" +
                    "Host: $effectiveSni\r\n" +
                    "User-Agent: Vexotun/1.0\r\n" +
                    "Connection: close\r\n\r\n"
                sock.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                sock.getOutputStream().flush()

                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                val statusLine = reader.readLine().orEmpty().trim()
                val interestingKeys = setOf("server", "location", "cf-ray", "upgrade", "via", "content-type")
                val headers = mutableListOf<Pair<String, String>>()
                var lineCount = 0
                while (lineCount < 32) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    lineCount++
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val k = line.substring(0, idx).trim()
                        val v = line.substring(idx + 1).trim()
                        if (k.lowercase() in interestingKeys) {
                            headers.add(k to v)
                        }
                    }
                }

                HostCheckResult(
                    ok = statusLine.isNotEmpty(),
                    host = targetHost,
                    port = targetPort,
                    tcpMs = tcpMs,
                    tlsMs = tlsMs,
                    tlsProtocol = tlsProto,
                    tlsVerified = tlsVerified,
                    certSubject = certSubject,
                    statusLine = statusLine.ifBlank { "TCP/TLS terhubung (tanpa respon HTTP)" },
                    headers = headers
                )
            }
        }.getOrElse { err ->
            HostCheckResult(
                ok = false,
                host = targetHost,
                port = targetPort,
                error = err.message ?: "Koneksi ke host gagal"
            )
        }
    }

    suspend fun lookupDns(
        domain: String,
        recordType: String,
        useTunnel: Boolean,
        socksPort: Int
    ): DnsLookupResult = withContext(Dispatchers.IO) {
        val cleanDomain = domain.trim().trimEnd('.')
        val cleanType = recordType.trim().uppercase().ifBlank { "A" }
        if (cleanDomain.isBlank()) {
            return@withContext DnsLookupResult(ok = false, domain = cleanDomain, type = cleanType, error = "Domain kosong")
        }
        val encName = URLEncoder.encode(cleanDomain, "UTF-8")
        val encType = URLEncoder.encode(cleanType, "UTF-8")
        val url = "https://dns.google/resolve?name=$encName&type=$encType"

        val raw = if (useTunnel) {
            TunnelState.fetchSocksUrl(socksPort, url, 9000L).getOrElse { err ->
                return@withContext DnsLookupResult(ok = false, domain = cleanDomain, type = cleanType, error = err.message ?: "DNS lookup gagal")
            }
        } else {
            runCatching { directGet(url, 7000) }.getOrElse { err ->
                return@withContext DnsLookupResult(ok = false, domain = cleanDomain, type = cleanType, error = err.message ?: "DNS lookup gagal")
            }
        }

        runCatching {
            val obj = JSONObject(raw)
            val status = obj.optInt("Status", -1)
            val arr = obj.optJSONArray("Answer")
            val records = mutableListOf<DnsRecordItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    records.add(
                        DnsRecordItem(
                            name = item.optString("name"),
                            ttl = item.optInt("TTL", 0),
                            data = item.optString("data")
                        )
                    )
                }
            }
            if (records.isEmpty()) {
                DnsLookupResult(
                    ok = status == 0,
                    domain = cleanDomain,
                    type = cleanType,
                    records = emptyList(),
                    error = if (status == 0) "Tidak ada record $cleanType" else "DNS status code: $status"
                )
            } else {
                DnsLookupResult(ok = true, domain = cleanDomain, type = cleanType, records = records)
            }
        }.getOrElse { err ->
            DnsLookupResult(ok = false, domain = cleanDomain, type = cleanType, error = err.message ?: "Parse DNS gagal")
        }
    }

    suspend fun runSpeedCheck(useTunnel: Boolean, socksPort: Int): SpeedCheckResult = withContext(Dispatchers.IO) {
        val url = "https://speed.cloudflare.com/__down?bytes=2000000"
        if (useTunnel) {
            val raw = TunnelState.speedTestSocks(socksPort, url, 15000L)
            return@withContext runCatching {
                val obj = JSONObject(raw)
                if (!obj.optBoolean("ok", false)) {
                    SpeedCheckResult(ok = false, viaTunnel = true, error = obj.optString("error", "Tes kecepatan gagal"))
                } else {
                    SpeedCheckResult(
                        ok = true,
                        viaTunnel = true,
                        bytes = obj.optLong("bytes", 0L),
                        elapsedMs = obj.optLong("ms", 0L),
                        mbps = obj.optDouble("mbps", 0.0)
                    )
                }
            }.getOrElse { err ->
                SpeedCheckResult(ok = false, viaTunnel = true, error = err.message ?: "Parse speed test gagal")
            }
        }

        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("User-Agent", "Vexotun/1.0")
            }
            try {
                val start = SystemClock.elapsedRealtime()
                val buf = ByteArray(16384)
                var total = 0L
                conn.inputStream.use { input ->
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        total += r
                    }
                }
                val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
                val mbps = (total * 8.0) / (elapsed * 1000.0)
                SpeedCheckResult(ok = true, viaTunnel = false, bytes = total, elapsedMs = elapsed, mbps = mbps)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { err ->
            SpeedCheckResult(ok = false, viaTunnel = false, error = err.message ?: "Tes kecepatan gagal")
        }
    }
}
