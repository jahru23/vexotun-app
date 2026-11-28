package com.vexotun.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VxtCodecTest {

    @Test
    fun encodeAndDecodeVxtWithAndWithoutPin() {
        val profile = TunnelProfile(
            name = "Test Reality",
            mode = TunnelMode.XRAY,
            xray = XrayProfile(
                protocol = XrayProtocol.VLESS,
                address = "1.2.3.4",
                port = 443,
                id = "11111111-1111-1111-1111-111111111111",
                securityMode = XraySecurityMode.REALITY,
                realityPublicKey = "pubkey-123"
            )
        )
        val lock = VxtLockInfo(locked = true, hideHost = true, author = "Jae")

        val noPinBytes = VxtCodec.encode(profile, lock)
        val decodedNoPin = VxtCodec.decode(noPinBytes).getOrThrow()
        assertEquals("Test Reality", decodedNoPin.name)
        assertTrue(decodedNoPin.isLocked)
        assertEquals("pubkey-123", decodedNoPin.xray.realityPublicKey)

        val pinBytes = VxtCodec.encode(profile, lock, pin = "4321")
        assertTrue(VxtCodec.requiresPin(pinBytes))
        assertTrue(VxtCodec.decode(pinBytes).exceptionOrNull() is VxtPinRequiredException)
        assertTrue(VxtCodec.decode(pinBytes, pin = "9999").isFailure)

        val decodedWithPin = VxtCodec.decode(pinBytes, pin = "4321").getOrThrow()
        assertEquals("Test Reality", decodedWithPin.name)
    }
}
