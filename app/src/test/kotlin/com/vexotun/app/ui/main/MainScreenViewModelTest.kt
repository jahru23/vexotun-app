package com.vexotun.app.ui.main

import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.TunnelProfile
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.model.XrayProfile
import com.vexotun.app.model.XrayProtocol
import com.vexotun.app.model.XraySecurityMode
import com.vexotun.app.model.XrayTransport
import com.vexotun.app.vpn.VexotunVpnService
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenViewModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyConfigJsonDecodesWithBackwardCompatibleDefaults() {
        val legacyJson = """
            {
              "profiles": [
                {
                  "id": "p1",
                  "name": "Legacy VMess TLS",
                  "mode": "XRAY",
                  "xray": {
                    "protocol": "VMESS",
                    "address": "legacy.example.com",
                    "port": 443,
                    "id": "11111111-1111-1111-1111-111111111111",
                    "network": "WS",
                    "tls": true
                  }
                }
              ],
              "activeProfileId": "p1"
            }
        """.trimIndent()

        val cfg = json.decodeFromString<VexotunConfig>(legacyJson)
        assertTrue(cfg.verifyXrayConnection)
        assertFalse(cfg.allowIpv6Routing)

        val active = cfg.activeProfile()!!
        assertEquals(TunnelMode.XRAY, active.mode)
        assertEquals(XraySecurityMode.TLS, active.xray.effectiveSecurityMode())
    }

    @Test
    fun defaultXrayProfileHasMuxDisabledForUniversalCompatibility() {
        val profile = XrayProfile()
        assertFalse(profile.muxEnabled)
        assertEquals(XraySecurityMode.TLS, profile.effectiveSecurityMode())
    }

    @Test
    fun sanitizeDnsIpHandlesIpv6PortAndRejectsOutOfRangeOctets() {
        assertEquals("2001:4860:4860:0:0:0:0:8888", VexotunVpnService.sanitizeDnsIp("[2001:4860:4860::8888]:53"))
        assertEquals("2001:4860:4860:0:0:0:0:8844", VexotunVpnService.sanitizeDnsIp("2001:4860:4860::8844"))
        assertEquals("1.1.1.1", VexotunVpnService.sanitizeDnsIp("1.1.1.1:53"))
        assertEquals("8.8.8.8", VexotunVpnService.sanitizeDnsIp("999.999.999.999"))
    }

    @Test
    fun validateProfileForStartRejectsIncompatibleXrayCustomPayload() {
        val realityPayload = TunnelProfile(
            name = "Reality Payload",
            mode = TunnelMode.XRAY,
            xray = XrayProfile(
                protocol = XrayProtocol.VLESS,
                address = "1.2.3.4",
                port = 443,
                id = "11111111-1111-1111-1111-111111111111",
                securityMode = XraySecurityMode.REALITY,
                tls = false,
                realityPublicKey = "pbk123",
                useCustomPayload = true
            )
        )
        assertNotNull(VexotunVpnService.validateProfileForStart(realityPayload))

        val wsDoubleUpgrade = TunnelProfile(
            name = "WS Double Upgrade",
            mode = TunnelMode.XRAY,
            xray = XrayProfile(
                protocol = XrayProtocol.VMESS,
                address = "1.2.3.4",
                port = 80,
                id = "11111111-1111-1111-1111-111111111111",
                network = XrayTransport.WS,
                securityMode = XraySecurityMode.NONE,
                tls = false,
                useCustomPayload = true
            )
        )
        assertNotNull(VexotunVpnService.validateProfileForStart(wsDoubleUpgrade))

        val validProxyConnect = wsDoubleUpgrade.copy(
            xray = wsDoubleUpgrade.xray.copy(
                proxyHost = "10.0.0.1",
                proxyPort = 8080,
                payload = "CONNECT [host_port] HTTP/1.1[crlf][crlf]"
            )
        )
        assertNull(VexotunVpnService.validateProfileForStart(validProxyConnect))
    }
}
