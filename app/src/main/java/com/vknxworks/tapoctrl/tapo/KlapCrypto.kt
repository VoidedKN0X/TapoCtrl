package com.vknxworks.tapoctrl.tapo

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KLAP, the local protocol of current Tapo firmware. Pure functions, no Android: unit-tested on the JVM.
 *
 * v2 (most Tapo devices): authHash = SHA256(SHA1(email) + SHA1(password)),
 *   handshake1 check = SHA256(local + remote + auth), handshake2 = SHA256(remote + local + auth).
 * v1 (older KLAP firmware): authHash = MD5(MD5(email) + MD5(password)),
 *   handshake1 check = SHA256(local + auth), handshake2 = SHA256(remote + auth).
 * The session keys are derived the same way for both.
 */
enum class KlapVersion { V2, V1 }

/** One way of logging in: a version and its 32-byte (v2) or 16-byte (v1) auth hash. */
class AuthHash(val version: KlapVersion, val hash: ByteArray)

object KlapCrypto {
    fun sha256(vararg parts: ByteArray): ByteArray = digest("SHA-256", *parts)
    fun sha1(vararg parts: ByteArray): ByteArray = digest("SHA-1", *parts)
    fun md5(vararg parts: ByteArray): ByteArray = digest("MD5", *parts)

    private fun digest(alg: String, vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance(alg)
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun authHashV2(email: String, password: String): ByteArray =
        sha256(sha1(email.toByteArray(Charsets.UTF_8)), sha1(password.toByteArray(Charsets.UTF_8)))

    fun authHashV1(email: String, password: String): ByteArray =
        md5(md5(email.toByteArray(Charsets.UTF_8)), md5(password.toByteArray(Charsets.UTF_8)))

    /** Both hashes for one account, v2 first. */
    fun authHashes(email: String, password: String): List<AuthHash> = listOf(
        AuthHash(KlapVersion.V2, authHashV2(email, password)),
        AuthHash(KlapVersion.V1, authHashV1(email, password)),
    )

    /** What the device must send back in handshake1 if it knows the same account. */
    fun serverHash(auth: AuthHash, local: ByteArray, remote: ByteArray): ByteArray = when (auth.version) {
        KlapVersion.V2 -> sha256(local, remote, auth.hash)
        KlapVersion.V1 -> sha256(local, auth.hash)
    }

    /** The body of handshake2. */
    fun clientHash(auth: AuthHash, local: ByteArray, remote: ByteArray): ByteArray = when (auth.version) {
        KlapVersion.V2 -> sha256(remote, local, auth.hash)
        KlapVersion.V1 -> sha256(remote, auth.hash)
    }

    /** The first of [candidates] whose server hash matches what the device sent, or null (wrong account). */
    fun match(candidates: List<AuthHash>, local: ByteArray, remote: ByteArray, server: ByteArray): AuthHash? =
        candidates.firstOrNull { MessageDigest.isEqual(serverHash(it, local, remote), server) }

    fun int32(b: ByteArray, at: Int): Int = ByteBuffer.wrap(b, at, 4).int
    fun bytes32(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()
}

/**
 * The encryption of one KLAP session. Not thread-safe on its own: [KlapClient] serialises requests.
 * key = SHA256("lsk"+local+remote+auth)[0:16]; ivFull = SHA256("iv"+local+remote+auth);
 * ivBase = ivFull[0:12]; seq = int32 BE of ivFull[28:32]; sig = SHA256("ldk"+local+remote+auth)[0:28].
 */
class KlapSession(local: ByteArray, remote: ByteArray, auth: ByteArray) {
    val key: ByteArray = KlapCrypto.sha256("lsk".toByteArray(), local, remote, auth).copyOf(16)
    private val ivFull = KlapCrypto.sha256("iv".toByteArray(), local, remote, auth)
    val ivBase: ByteArray = ivFull.copyOf(12)
    var seq: Int = KlapCrypto.int32(ivFull, 28)
        private set
    val sig: ByteArray = KlapCrypto.sha256("ldk".toByteArray(), local, remote, auth).copyOf(28)

    fun iv(seq: Int): ByteArray = ivBase + KlapCrypto.bytes32(seq)

    /** Encrypts the next request: returns its sequence number and the body (signature + ciphertext). */
    fun encrypt(plain: ByteArray): Pair<Int, ByteArray> {
        seq += 1 // Int overflow wraps to Int.MIN_VALUE, like the device's signed counter
        val s = seq
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv(s)))
        val ct = cipher.doFinal(plain)
        val signature = KlapCrypto.sha256(sig, KlapCrypto.bytes32(s), ct)
        return s to (signature + ct)
    }

    /** Decrypts a response to request [seq]: the first 32 bytes are the device's signature. */
    fun decrypt(seq: Int, body: ByteArray): ByteArray {
        require(body.size > 32) { "Response too short" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv(seq)))
        return cipher.doFinal(body, 32, body.size - 32)
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
