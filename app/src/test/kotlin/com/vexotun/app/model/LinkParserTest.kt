package com.vexotun.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {

    @Test
    fun parseVlessRealityLinkWithSpacesAndEmojiInRemark() {
        val link = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443" +
            "?type=tcp&security=reality&sni=www.google.com&fp=chrome" +
            "&pbk=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&sid=12ab34cd&spx=%2Fhome&flow=xtls-rprx-vision" +
            "#SG 01 [VIP] 🚀"

        val profile = LinkParser.parseShareLink(link).getOrThrow()
        assertEquals("SG 01 [VIP] 🚀", profile.name)
        assertEquals(TunnelMode.XRAY, profile.mode)
        assertEquals(XrayProtocol.VLESS, profile.xray.protocol)
        assertEquals("1.2.3.4", profile.xray.address)
        assertEquals(443, profile.xray.port)
        assertEquals(XraySecurityMode.REALITY, profile.xray.effectiveSecurityMode())
        assertEquals("www.google.com", profile.xray.sni)
        assertEquals("chrome", profile.xray.fingerprint)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", profile.xray.realityPublicKey)
        assertEquals("12ab34cd", profile.xray.realityShortId)
        assertEquals("/home", profile.xray.realitySpiderX)
        assertEquals("xtls-rprx-vision", profile.xray.flow)
        assertFalse(profile.xray.muxEnabled)
    }

    @Test
    fun parseTrojanWithoutSecurityParamDefaultsToTls() {
        val link = "trojan://secretpass@sg.example.com:443?type=ws&path=%2Ftr#My Trojan [ID]"
        val profile = LinkParser.parseShareLink(link).getOrThrow()

        assertEquals("My Trojan [ID]", profile.name)
        assertEquals(XrayProtocol.TROJAN, profile.xray.protocol)
        assertTrue(profile.xray.tls)
        assertEquals(XraySecurityMode.TLS, profile.xray.effectiveSecurityMode())
        assertEquals(XrayTransport.WS, profile.xray.network)
        assertEquals("/tr", profile.xray.path)
    }

    @Test
    fun parseVlessTlsWithVcnPcsAlpnAndTcpHttp() {
        val link = "vless://22222222-2222-2222-2222-222222222222@cdn.example.com:80" +
            "?type=tcp&headerType=http&host=bug.example.com&path=%2Fws&security=tls" +
            "&alpn=http%2F1.1&vcn=origin.example.com&pcs=deadbeef&fp=safari&allowInsecure=1#HTTP-TCP"

        val profile = LinkParser.parseShareLink(link).getOrThrow()
        assertEquals(XrayTransport.TCP, profile.xray.network)
        assertEquals("http", profile.xray.headerType)
        assertEquals("bug.example.com", profile.xray.host)
        assertEquals("/ws", profile.xray.path)
        assertEquals("http/1.1", profile.xray.alpn)
        assertEquals("origin.example.com", profile.xray.verifyPeerCertByName)
        assertEquals("deadbeef", profile.xray.pinnedPeerCertSha256)
        assertEquals("safari", profile.xray.fingerprint)
        assertTrue(profile.xray.allowInsecure)
    }

    @Test
    fun parseShadowsocksIpv6AndSip002() {
        val link = "ss://aes-256-gcm:myPass123@[2001:db8::1]:8388#SS IPv6 Node"
        val profile = LinkParser.parseShareLink(link).getOrThrow()

        assertEquals(XrayProtocol.SHADOWSOCKS, profile.xray.protocol)
        assertEquals("2001:db8::1", profile.xray.address)
        assertEquals(8388, profile.xray.port)
        assertEquals("aes-256-gcm", profile.xray.security)
        assertEquals("myPass123", profile.xray.id)
        assertEquals("SS IPv6 Node", profile.name)
    }

    @Test
    fun parseMultipleLinksWithSpacesInRemarks() {
        val input = """
            vless://11111111-1111-1111-1111-111111111111@a.example.com:443?type=ws&security=tls#SG Premium 01
            trojan://pass@b.example.com:443#ID Gaming 02
        """.trimIndent()

        val list = LinkParser.parseShareLinks(input).getOrThrow()
        assertEquals(2, list.size)
        assertEquals("SG Premium 01", list[0].name)
        assertEquals("ID Gaming 02", list[1].name)
        assertTrue(list[1].xray.tls)
    }

    @Test
    fun exportAndReimportRoundTrip() {
        val reality = TunnelProfile(
            name = "Reality Node",
            mode = TunnelMode.XRAY,
            xray = XrayProfile(
                protocol = XrayProtocol.VLESS,
                address = "198.51.100.10",
                port = 443,
                id = "11111111-1111-1111-1111-111111111111",
                flow = "xtls-rprx-vision",
                network = XrayTransport.XHTTP,
                xhttpMode = "packet-up",
                path = "/xhttp",
                host = "cdn.example.com",
                tls = false,
                securityMode = XraySecurityMode.REALITY,
                sni = "www.cloudflare.com",
                fingerprint = "chrome",
                realityPublicKey = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                realityShortId = "abcd",
                realitySpiderX = "/spider"
            )
        )

        val link = LinkExporter.exportShareLink(reality).getOrThrow()
        val parsed = LinkParser.parseShareLink(link).getOrThrow()

        assertEquals(reality.name, parsed.name)
        assertEquals(XraySecurityMode.REALITY, parsed.xray.effectiveSecurityMode())
        assertEquals("packet-up", parsed.xray.xhttpMode)
        assertEquals(reality.xray.realityPublicKey, parsed.xray.realityPublicKey)
        assertEquals(reality.xray.realityShortId, parsed.xray.realityShortId)
        assertEquals(reality.xray.realitySpiderX, parsed.xray.realitySpiderX)
        assertEquals(reality.xray.flow, parsed.xray.flow)
    }

    @Test
    fun parseTrojanWithEmptySecurityAndShadowsocksPasswordWithAtSign() {
        val trojan = LinkParser.parseShareLink("trojan://secret@node.example.com:443?security=#TrojanEmptySec").getOrThrow()
        assertEquals(XraySecurityMode.TLS, trojan.xray.effectiveSecurityMode())
        assertTrue(trojan.xray.tls)

        val ss = LinkParser.parseShareLink("ss://aes-256-gcm:p@ssword!@198.51.100.77:8388#SSAt").getOrThrow()
        assertEquals("aes-256-gcm", ss.xray.security)
        assertEquals("p@ssword!", ss.xray.id)
        assertEquals("198.51.100.77", ss.xray.address)
        assertEquals(8388, ss.xray.port)
    }
}
