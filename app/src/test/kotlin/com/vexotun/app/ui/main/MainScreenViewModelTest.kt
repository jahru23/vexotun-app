package com.vexotun.app.ui.main

import com.vexotun.app.model.TunnelMode
import com.vexotun.app.model.VexotunConfig
import com.vexotun.app.model.XrayProfile
import com.vexotun.app.model.XraySecurityMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
