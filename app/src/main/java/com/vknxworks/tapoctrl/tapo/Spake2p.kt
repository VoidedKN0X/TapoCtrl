package com.vknxworks.tapoctrl.tapo

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * TPAP, the login of Tapo cameras on recent firmware (they answer the older encrypt_type 3 login with -40211):
 * SPAKE2+ (RFC 9383) on P-256 with SHA-256, HKDF-SHA256 and HMAC-SHA256, then AES-128-CCM on "/stok=…/ds".
 * Pure functions, no Android: unit-tested on the JVM against the RFC 9383 test vector and the known answers of
 * the tapo crate (tpap_spake2p.rs, tpap_cipher.rs, crypto.rs) and pytapo (transport/tpap/spake2p.py).
 */

/** P-256 in affine coordinates, with plain BigInteger arithmetic. `null` is the point at infinity. */
internal object P256 {
    val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A = P.subtract(BigInteger.valueOf(3))
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    val G = Point(
        BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
        BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16),
    )

    data class Point(val x: BigInteger, val y: BigInteger)

    fun onCurve(p: Point): Boolean {
        if (p.x.signum() < 0 || p.x >= P || p.y.signum() < 0 || p.y >= P) return false
        val left = p.y.multiply(p.y).mod(P)
        val right = p.x.pow(3).add(A.multiply(p.x)).add(B).mod(P)
        return left == right
    }

    fun add(p1: Point?, p2: Point?): Point? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        if (p1.x == p2.x && p1.y.add(p2.y).mod(P).signum() == 0) return null
        val m = if (p1 == p2) {
            BigInteger.valueOf(3).multiply(p1.x).multiply(p1.x).add(A).multiply(p1.y.shiftLeft(1).modInverse(P)).mod(P)
        } else {
            p2.y.subtract(p1.y).multiply(p2.x.subtract(p1.x).mod(P).modInverse(P)).mod(P)
        }
        val x3 = m.multiply(m).subtract(p1.x).subtract(p2.x).mod(P)
        return Point(x3, m.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P))
    }

    fun neg(p: Point?): Point? = p?.let { Point(it.x, P.subtract(it.y).mod(P)) }

    fun mul(k: BigInteger, p: Point?): Point? {
        var n = k.mod(N)
        var result: Point? = null
        var addend = p
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            n = n.shiftRight(1)
        }
        return result
    }

    /** SEC1: 0x04 uncompressed, or 0x02/0x03 compressed. Null for anything that isn't a point on the curve. */
    fun decode(b: ByteArray): Point? {
        val p = when {
            b.size == 65 && b[0].toInt() == 4 -> Point(BigInteger(1, b.copyOfRange(1, 33)), BigInteger(1, b.copyOfRange(33, 65)))
            b.size == 33 && (b[0].toInt() == 2 || b[0].toInt() == 3) -> {
                val x = BigInteger(1, b.copyOfRange(1, 33))
                if (x >= P) return null
                var y = x.pow(3).add(A.multiply(x)).add(B).mod(P).modPow(P.add(BigInteger.ONE).shiftRight(2), P)
                if (y.testBit(0) != (b[0].toInt() and 1 == 1)) y = P.subtract(y)
                Point(x, y)
            }
            else -> return null
        }
        return p.takeIf { onCurve(it) }
    }

    fun encode(p: Point): ByteArray = byteArrayOf(4) + fixed(p.x) + fixed(p.y)

    /** [v] as exactly 32 big-endian bytes. */
    fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}

internal object TpapCrypto {
    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC pads a short key with zero bytes, so an empty key is the same as 32 zero bytes.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869). An empty [salt] means 32 zero bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmacSha256(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018) over the UTF-8 bytes of [password]. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(32) else password, "HmacSHA256"))
        val out = java.io.ByteArrayOutputStream()
        var block = 1
        while (out.size() < length) {
            var u = mac.doFinal(salt + ByteBuffer.allocate(4).putInt(block).array())
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            out.write(t)
            block++
        }
        return out.toByteArray().copyOf(length)
    }

    // ---- SHA-256-crypt ("$5$", Ulrich Drepper's specification), as `openssl passwd -5` -------------------------

    private const val CRYPT_B64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val CRYPT_ORDER = arrayOf(
        intArrayOf(0, 10, 20), intArrayOf(21, 1, 11), intArrayOf(12, 22, 2), intArrayOf(3, 13, 23), intArrayOf(24, 4, 14),
        intArrayOf(15, 25, 5), intArrayOf(6, 16, 26), intArrayOf(27, 7, 17), intArrayOf(18, 28, 8), intArrayOf(9, 19, 29),
    )

    /**
     * SHA-256-crypt of [key] with [salt] (cut to 16 characters) and [rounds] (5000 when null; then the result has
     * no "rounds=" part). Returns the whole "$5$[rounds=N$]salt$hash" string.
     */
    fun sha256Crypt(key: ByteArray, salt: String, rounds: Int?): String {
        val r = (rounds ?: 5000).coerceIn(1000, 999_999_999)
        val explicit = rounds != null && rounds != 5000
        val s = salt.take(16).toByteArray(Charsets.UTF_8)
        fun md() = MessageDigest.getInstance("SHA-256")
        fun repeat(digest: ByteArray, length: Int): ByteArray = ByteArray(length) { digest[it % 32] }
        val b = md().run { update(key); update(s); update(key); digest() }
        val a = md().run {
            update(key); update(s); update(repeat(b, key.size))
            var n = key.size
            while (n > 0) {
                update(if (n and 1 == 1) b else key)
                n = n shr 1
            }
            digest()
        }
        val p = repeat(md().run { repeat(key.size) { update(key) }; digest() }, key.size)
        val sb = repeat(md().run { repeat(16 + (a[0].toInt() and 0xFF)) { update(s) }; digest() }, s.size)
        var c = a
        for (i in 0 until r) {
            c = md().run {
                update(if (i and 1 == 1) p else c)
                if (i % 3 != 0) update(sb)
                if (i % 7 != 0) update(p)
                update(if (i and 1 == 1) c else p)
                digest()
            }
        }
        fun u(i: Int) = c[i].toInt() and 0xFF
        val enc = StringBuilder()
        fun to64(value: Int, n: Int) {
            var v = value
            repeat(n) { enc.append(CRYPT_B64[v and 0x3F]); v = v shr 6 }
        }
        for (o in CRYPT_ORDER) to64((u(o[0]) shl 16) or (u(o[1]) shl 8) or u(o[2]), 4)
        to64((u(31) shl 8) or u(30), 3)
        val roundsPart = if (explicit) "rounds=$r$" else ""
        return "$5$" + roundsPart + String(s, Charsets.UTF_8) + "$" + enc
    }

    /** Reads "$5$[rounds=N$]salt$…" into (salt, rounds); null when it isn't such a prefix. */
    fun parseCryptPrefix(prefix: String): Pair<String, Int?>? {
        if (!prefix.startsWith("$5$")) return null
        var rest = prefix.substring(3)
        var rounds: Int? = null
        if (rest.startsWith("rounds=")) {
            val end = rest.indexOf('$')
            if (end < 0) return null
            rounds = rest.substring(7, end).toIntOrNull() ?: return null
            rest = rest.substring(end + 1)
        }
        val salt = rest.substringBefore('$')
        return if (salt.isEmpty()) null else salt to rounds
    }

    // ---- AES-128-CCM (RFC 3610) with a 12-byte nonce and a 16-byte tag, no associated data ---------------------

    private fun aesBlock(key: ByteArray): (ByteArray) -> ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return { block -> c.doFinal(block) }
    }

    private fun ccmCounter(nonce: ByteArray, i: Int): ByteArray =
        byteArrayOf(2) + nonce + byteArrayOf((i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())

    private fun ccmMac(e: (ByteArray) -> ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        require(nonce.size == 12 && plain.size < (1 shl 24))
        // Flags: no associated data, M' = (16 - 2) / 2 = 7, L' = 3 - 1 = 2.
        val b0 = byteArrayOf(0x3A) + nonce + byteArrayOf((plain.size ushr 16).toByte(), (plain.size ushr 8).toByte(), plain.size.toByte())
        var x = e(b0)
        var off = 0
        while (off < plain.size) {
            val block = ByteArray(16)
            System.arraycopy(plain, off, block, 0, minOf(16, plain.size - off))
            for (k in 0 until 16) block[k] = (block[k].toInt() xor x[k].toInt()).toByte()
            x = e(block)
            off += 16
        }
        return x
    }

    private fun ccmCtr(e: (ByteArray) -> ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var off = 0
        var i = 1
        while (off < data.size) {
            val s = e(ccmCounter(nonce, i))
            for (k in 0 until minOf(16, data.size - off)) out[off + k] = (data[off + k].toInt() xor s[k].toInt()).toByte()
            off += 16
            i++
        }
        return out
    }

    /** Ciphertext followed by its 16-byte tag. */
    fun ccmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        val e = aesBlock(key)
        val t = ccmMac(e, nonce, plain)
        val s0 = e(ccmCounter(nonce, 0))
        val tag = ByteArray(16) { (t[it].toInt() xor s0[it].toInt()).toByte() }
        return ccmCtr(e, nonce, plain) + tag
    }

    /** The plaintext, or an exception when the tag doesn't match (wrong key, nonce or a changed message). */
    fun ccmDecrypt(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 16) throw java.security.GeneralSecurityException("CCM: too short")
        val e = aesBlock(key)
        val plain = ccmCtr(e, nonce, data.copyOfRange(0, data.size - 16))
        val t = ccmMac(e, nonce, plain)
        val s0 = e(ccmCounter(nonce, 0))
        val tag = ByteArray(16) { (t[it].toInt() xor s0[it].toInt()).toByte() }
        if (!MessageDigest.isEqual(tag, data.copyOfRange(data.size - 16, data.size))) {
            throw java.security.GeneralSecurityException("CCM: tag mismatch")
        }
        return plain
    }
}

/** What the client holds after one SPAKE2+ exchange. */
internal class Spake2pProof(
    val share: ByteArray,
    val confirm: ByteArray,
    val sharedKey: ByteArray,
    private val verifierConfirmKey: ByteArray,
) {
    /** Whether the camera proved that it knows the same password. */
    fun verify(deviceConfirm: ByteArray): Boolean =
        MessageDigest.isEqual(TpapCrypto.hmacSha256(verifierConfirmKey, share), deviceConfirm)
}

/** The prover side of SPAKE2+ (RFC 9383) as TPAP uses it. */
internal object Spake2p {
    private val M = P256.decode("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f".unhex())!!
    private val N = P256.decode("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49".unhex())!!

    /** At most this many PBKDF2 rounds, so a misbehaving device can't make the phone work for minutes. */
    const val MAX_ITERATIONS = 100_000

    /** w0 and w1: the two 40-byte halves of PBKDF2(credential, salt, iterations, 80), each reduced mod n. */
    fun w0w1(credential: String, salt: ByteArray, iterations: Int): Pair<BigInteger, BigInteger> {
        val dk = TpapCrypto.pbkdf2(credential.toByteArray(Charsets.UTF_8), salt, iterations, 80)
        return BigInteger(1, dk.copyOfRange(0, 40)).mod(P256.N) to BigInteger(1, dk.copyOfRange(40, 80)).mod(P256.N)
    }

    /** The context TPAP binds the exchange to: SHA256("PAKE V1" + user_random + dev_random). */
    fun context(userRandom: ByteArray, deviceRandom: ByteArray): ByteArray =
        TpapCrypto.sha256("PAKE V1".toByteArray(Charsets.US_ASCII), userRandom, deviceRandom)

    /**
     * One SPAKE2+ exchange with the random scalar [x]. Null when the device's share isn't a usable point on the
     * curve (then the login stops).
     */
    fun prove(
        w0: BigInteger,
        w1: BigInteger,
        x: BigInteger,
        context: ByteArray,
        proverId: ByteArray,
        verifierId: ByteArray,
        verifierShare: ByteArray,
    ): Spake2pProof? {
        val y = P256.decode(verifierShare) ?: return null
        val share = P256.add(P256.mul(x, P256.G), P256.mul(w0, M)) ?: return null
        val unmasked = P256.add(y, P256.neg(P256.mul(w0, N))) ?: return null
        val z = P256.mul(x, unmasked) ?: return null
        val v = P256.mul(w1, unmasked) ?: return null
        val shareBytes = P256.encode(share)
        val yBytes = P256.encode(y)
        val transcript = java.io.ByteArrayOutputStream()
        for (item in listOf(context, proverId, verifierId, P256.encode(M), P256.encode(N), shareBytes, yBytes, P256.encode(z), P256.encode(v), P256.fixed(w0))) {
            transcript.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(item.size.toLong()).array())
            transcript.write(item)
        }
        val ke = TpapCrypto.sha256(transcript.toByteArray())
        val conf = TpapCrypto.hkdf(ke, ByteArray(0), "ConfirmationKeys".toByteArray(), 64)
        return Spake2pProof(
            share = shareBytes,
            confirm = TpapCrypto.hmacSha256(conf.copyOfRange(0, 32), yBytes),
            sharedKey = TpapCrypto.hkdf(ke, ByteArray(0), "SharedKey".toByteArray(), 32),
            verifierConfirmKey = conf.copyOfRange(32, 64),
        )
    }
}

/**
 * The encrypted channel after a TPAP login: AES-128-CCM with key and base nonce from HKDF of the shared key.
 * Each request carries its 4-byte sequence number (big-endian), then ciphertext and tag; the nonce is the first
 * 8 bytes of the base nonce followed by that sequence number. The reply comes under the same number.
 */
internal class TpapSession(sharedKey: ByteArray, startSeq: Int) {
    val key: ByteArray = TpapCrypto.hkdf(sharedKey, "tp-kdf-salt-aes128-key".toByteArray(), "tp-kdf-info-aes128-key".toByteArray(), 32).copyOf(16)
    private val baseNonce = TpapCrypto.hkdf(sharedKey, "tp-kdf-salt-aes128-iv".toByteArray(), "tp-kdf-info-aes128-iv".toByteArray(), 32).copyOf(12)
    var seq: Int = startSeq
        private set

    fun nonce(seq: Int): ByteArray = baseNonce.copyOf(8) + ByteBuffer.allocate(4).putInt(seq).array()

    /** The next request body and its sequence number. */
    fun encrypt(plain: ByteArray): Pair<Int, ByteArray> {
        val s = seq
        seq += 1
        return s to (ByteBuffer.allocate(4).putInt(s).array() + TpapCrypto.ccmEncrypt(key, nonce(s), plain))
    }

    /** The reply to request [seq]; an exception when it's for another request or doesn't authenticate. */
    fun decrypt(seq: Int, body: ByteArray): ByteArray {
        if (body.size < 20) throw java.security.GeneralSecurityException("TPAP: reply too short")
        val replySeq = ByteBuffer.wrap(body, 0, 4).int
        if (replySeq != seq) throw java.security.GeneralSecurityException("TPAP: reply to another request")
        return TpapCrypto.ccmDecrypt(key, nonce(seq), body.copyOfRange(4, body.size))
    }
}
