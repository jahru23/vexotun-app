package com.vexotun.app.model

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
private data class VxtEnvelope(
    val version: Int = 1,
    val profile: TunnelProfile
)

object VxtCodec {
    private const val HEADER = "VXT1:"
    private const val KEY_SEED = "Vexotun-Config-VXT1-AES256-GCM-2026"
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    private fun deriveKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    fun encode(profile: TunnelProfile, lockInfo: VxtLockInfo): ByteArray {
        val normalizedLock = if (lockInfo.hideHost || lockInfo.hideSni || lockInfo.hideAuth || lockInfo.hidePayload) {
            lockInfo.copy(locked = true)
        } else {
            lockInfo
        }
        val cleanProfile = profile.copy(
            id = UUID.randomUUID().toString(),
            lockInfo = normalizedLock
        )
        val plain = json.encodeToString(VxtEnvelope(version = 1, profile = cleanProfile)).toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)

        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

        val encoded = Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return (HEADER + encoded).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): Result<TunnelProfile> = runCatching {
        val raw = bytes.toString(Charsets.UTF_8).trim()
        require(raw.startsWith(HEADER)) { "Format file .vxt tidak dikenali" }

        val payloadB64 = raw.removePrefix(HEADER).trim()
        val combined = Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_WRAP)
        require(combined.size > 12) { "Data file .vxt rusak" }

        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)

        val envelope = json.decodeFromString<VxtEnvelope>(plain)
        envelope.profile.copy(id = UUID.randomUUID().toString())
    }
}
