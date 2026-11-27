package com.vexotun.app.model

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class VxtPinRequiredException : IllegalStateException("File .vxt ini dilindungi PIN")

@Serializable
private data class VxtEnvelope(
    val version: Int = 1,
    val profile: TunnelProfile
)

object VxtCodec {
    private const val HEADER_V1 = "VXT1:"
    private const val HEADER_V2 = "VXT2:"
    private const val KEY_SEED = "Vexotun-Config-VXT1-AES256-GCM-2026"
    private const val PBKDF2_ITERATIONS = 12_000
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    private fun deriveLegacyKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    private fun derivePinKey(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec("$KEY_SEED:$pin".toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun requiresPin(rawOrBytes: ByteArray): Boolean =
        rawOrBytes.toString(Charsets.UTF_8).trim().startsWith(HEADER_V2)

    fun encode(profile: TunnelProfile, lockInfo: VxtLockInfo, pin: String = ""): ByteArray {
        val normalizedLock = if (
            lockInfo.hideHost ||
            lockInfo.hideSni ||
            lockInfo.hideAuth ||
            lockInfo.hidePayload ||
            lockInfo.hideDns
        ) {
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
        val cleanPin = pin.trim()

        return if (cleanPin.isEmpty()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveLegacyKey(), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plain)
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            val encoded = Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            (HEADER_V1 + encoded).toByteArray(Charsets.UTF_8)
        } else {
            val salt = ByteArray(16).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, derivePinKey(cleanPin, salt), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plain)
            val combined = ByteArray(salt.size + iv.size + encrypted.size)
            System.arraycopy(salt, 0, combined, 0, salt.size)
            System.arraycopy(iv, 0, combined, salt.size, iv.size)
            System.arraycopy(encrypted, 0, combined, salt.size + iv.size, encrypted.size)
            val encoded = Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            (HEADER_V2 + encoded).toByteArray(Charsets.UTF_8)
        }
    }

    fun decode(bytes: ByteArray, pin: String = ""): Result<TunnelProfile> = runCatching {
        val raw = bytes.toString(Charsets.UTF_8).trim()
        val plainBytes: ByteArray = when {
            raw.startsWith(HEADER_V1) -> {
                val payloadB64 = raw.removePrefix(HEADER_V1).trim()
                val combined = Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_WRAP)
                require(combined.size > 12) { "Data file .vxt rusak" }
                val iv = combined.copyOfRange(0, 12)
                val ciphertext = combined.copyOfRange(12, combined.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveLegacyKey(), GCMParameterSpec(128, iv))
                cipher.doFinal(ciphertext)
            }
            raw.startsWith(HEADER_V2) -> {
                val cleanPin = pin.trim()
                if (cleanPin.isEmpty()) throw VxtPinRequiredException()
                val payloadB64 = raw.removePrefix(HEADER_V2).trim()
                val combined = Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_WRAP)
                require(combined.size > 28) { "Data file .vxt rusak" }
                val salt = combined.copyOfRange(0, 16)
                val iv = combined.copyOfRange(16, 28)
                val ciphertext = combined.copyOfRange(28, combined.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, derivePinKey(cleanPin, salt), GCMParameterSpec(128, iv))
                try {
                    cipher.doFinal(ciphertext)
                } catch (_: AEADBadTagException) {
                    throw IllegalArgumentException("PIN / password config .vxt salah")
                }
            }
            else -> throw IllegalArgumentException("Format file .vxt tidak dikenali")
        }

        val envelope = json.decodeFromString<VxtEnvelope>(plainBytes.toString(Charsets.UTF_8))
        val decoded = envelope.profile.copy(id = UUID.randomUUID().toString())
        require(decoded.lockInfo?.isExpired() != true) { "Config .vxt sudah kedaluwarsa" }
        decoded
    }
}
