package com.vexotun.app.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderTest {

    @Test
    fun buildJsonRealityAndXhttpMode() {
        val profile = XrayProfile(
            protocol = XrayProtocol.VLESS,
            address = "203.0.113.5",
            port = 443,
            id = "11111111-1111-1111-1111-111111111111",
            network = XrayTransport.XHTTP,
            xhttpMode = "stream-up",
            path = "/api",
            host = "cdn.example.com",
            securityMode = XraySecurityMode.REALITY,
            sni = "dl.google.com",
            fingerprint = "firefox",
            realityPublicKey = "ccccccccccccccccccccccccccccccccccccccccccc",
            realityShortId = "01ff",
            realitySpiderX = "/docs"
        )

        val root = Json.parseToJsonElement(XrayConfigBuilder.buildJson(profile)).jsonObject
        val outbound = root["outbounds"]!!.jsonArray[0].jsonObject
        val stream = outbound["streamSettings"]!!.jsonObject

        assertEquals("xhttp", stream["network"]!!.jsonPrimitive.content)
        assertEquals("reality", stream["security"]!!.jsonPrimitive.content)

        val reality = stream["realitySettings"]!!.jsonObject
        assertEquals("dl.google.com", reality["serverName"]!!.jsonPrimitive.content)
        assertEquals("firefox", reality["fingerprint"]!!.jsonPrimitive.content)
        assertEquals("ccccccccccccccccccccccccccccccccccccccccccc", reality["publicKey"]!!.jsonPrimitive.content)
        assertEquals("01ff", reality["shortId"]!!.jsonPrimitive.content)
        assertEquals("/docs", reality["spiderX"]!!.jsonPrimitive.content)

        val xhttp = stream["xhttpSettings"]!!.jsonObject
        assertEquals("stream-up", xhttp["mode"]!!.jsonPrimitive.content)
        assertNull(outbound["mux"])
    }

    @Test
    fun buildJsonTlsIncludesVcnPcsAndOmitsJsonAllowInsecure() {
        val profile = XrayProfile(
            protocol = XrayProtocol.TROJAN,
            address = "198.51.100.2",
            port = 443,
            id = "pass",
            network = XrayTransport.GRPC,
            path = "tun",
            grpcMultiMode = true,
            tls = true,
            securityMode = XraySecurityMode.TLS,
            sni = "bug.example.com",
            alpn = "h2,http/1.1",
            fingerprint = "safari",
            verifyPeerCertByName = "real.example.com",
            pinnedPeerCertSha256 = "0011223344",
            allowInsecure = true,
            muxEnabled = true
        )

        val root = Json.parseToJsonElement(XrayConfigBuilder.buildJson(profile)).jsonObject
        val outbound = root["outbounds"]!!.jsonArray[0].jsonObject
        val stream = outbound["streamSettings"]!!.jsonObject
        val tls = stream["tlsSettings"]!!.jsonObject

        assertEquals("bug.example.com", tls["serverName"]!!.jsonPrimitive.content)
        assertEquals("safari", tls["fingerprint"]!!.jsonPrimitive.content)
        assertEquals("real.example.com", tls["verifyPeerCertByName"]!!.jsonPrimitive.content)
        assertEquals("0011223344", tls["pinnedPeerCertSha256"]!!.jsonPrimitive.content)
        assertNull(tls["allowInsecure"])
        assertEquals(2, tls["alpn"]!!.jsonArray.size)

        val grpc = stream["grpcSettings"]!!.jsonObject
        assertTrue(grpc["multiMode"]!!.jsonPrimitive.booleanOrNull == true)
        assertNull(outbound["mux"])
    }

    @Test
    fun buildJsonTcpHttpHeaderAndNeverMuxShadowsocks() {
        val ss = XrayProfile(
            protocol = XrayProtocol.SHADOWSOCKS,
            address = "1.2.3.4",
            port = 8388,
            id = "pass",
            security = "aes-256-gcm",
            network = XrayTransport.TCP,
            headerType = "http",
            host = "m.facebook.com",
            path = "/feed",
            tls = false,
            muxEnabled = true
        )

        val root = Json.parseToJsonElement(XrayConfigBuilder.buildJson(ss, muxEnabled = true)).jsonObject
        val outbound = root["outbounds"]!!.jsonArray[0].jsonObject
        assertNull(outbound["mux"])

        val tcp = outbound["streamSettings"]!!.jsonObject["tcpSettings"]!!.jsonObject
        val header = tcp["header"]!!.jsonObject
        assertEquals("http", header["type"]!!.jsonPrimitive.content)
        val req = header["request"]!!.jsonObject
        assertEquals("/feed", req["path"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("m.facebook.com", req["headers"]!!.jsonObject["Host"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun buildJsonWsUsesDirectHostFieldWithoutDeprecationOnlyHeaders() {
        val profile = XrayProfile(
            protocol = XrayProtocol.VMESS,
            address = "1.1.1.1",
            port = 443,
            id = "11111111-1111-1111-1111-111111111111",
            network = XrayTransport.WS,
            path = "/vmess",
            host = "cdn.example.com",
            tls = true
        )

        val root = Json.parseToJsonElement(XrayConfigBuilder.buildJson(profile)).jsonObject
        val ws = root["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!.jsonObject["wsSettings"]!!.jsonObject
        assertEquals("cdn.example.com", ws["host"]!!.jsonPrimitive.content)
        assertEquals("/vmess", ws["path"]!!.jsonPrimitive.content)
        assertFalse(XrayProfile().muxEnabled)
    }
}
