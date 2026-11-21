package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.Base64
import javax.crypto.Cipher

/*
 * securePassthrough, the local protocol of older Tapo plug and bulb firmware (before KLAP), as python-kasa
 * (transports/aestransport.py) and plugp100 (api/protocol/securepassthrough_transport.py, passthrough_protocol.py)
 * implement it:
 * 1. POST /app {"method":"handshake","params":{"key": <RSA-1024 public key, PEM>}}: the device answers with a
 *    session cookie and result.key = RSA(PKCS#1 v1.5) of 32 bytes: AES key (16) + IV (16).
 * 2. login_device inside securePassthrough: username = Base64(SHA-1 hex of the email); login version 1 sends
 *    password = Base64(the password itself), version 2 sends password2 = Base64(SHA-1 hex of the password).
 *    The answer has a token.
 * 3. Every request: POST /app?token=… {"method":"securePassthrough","params":{"request": Base64(AES-CBC(json))}}.
 * Pure functions, unit-tested on the JVM.
 */

/** What older firmware checks: hashes of your email and password, and the password itself only if you chose to keep it. */
class LegacySecret(val emailSha1: ByteArray, val passwordSha1: ByteArray, val password: String?) {
    companion object {
        fun of(email: String, password: String, keepPassword: Boolean): LegacySecret {
            fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            return LegacySecret(sha1(email), sha1(password), password.takeIf { keepPassword })
        }
    }
}

internal object PassthroughCrypto {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** A fresh RSA-1024 key pair for one handshake; the private key never leaves memory. */
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()

    /** The public key as python-kasa sends it: one line of Base64 DER between the PEM markers, each line ending in \n. */
    fun publicPem(kp: KeyPair): String =
        "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(kp.public.encoded) + "\n-----END PUBLIC KEY-----\n"

    fun handshakeBody(kp: KeyPair): String =
        buildJsonObject { put("method", "handshake"); putJsonObject("params") { put("key", publicPem(kp)) } }.toString()

    /** The session's AES key and IV from the handshake reply's key; an exception when it doesn't decrypt to 32 bytes. */
    fun sessionKey(private: PrivateKey, keyB64: String): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.DECRYPT_MODE, private)
        val plain = c.doFinal(Base64.getDecoder().decode(keyB64))
        if (plain.size != 32) throw java.security.GeneralSecurityException("Handshake key of ${plain.size} bytes")
        return plain.copyOfRange(0, 16) to plain.copyOfRange(16, 32)
    }

    /**
     * login_device's params. [v2]: password2 with the SHA-1 hex of the password; otherwise (version 1) the password
     * itself, which only exists when you chose to keep it (null then).
     */
    fun loginParams(secret: LegacySecret, v2: Boolean): JsonObject? {
        val user = b64(secret.emailSha1.hex())
        return if (v2) {
            buildJsonObject { put("password2", b64(secret.passwordSha1.hex())); put("username", user) }
        } else {
            val pw = secret.password ?: return null
            buildJsonObject { put("password", b64(pw)); put("username", user) }
        }
    }
}
