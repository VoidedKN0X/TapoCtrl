package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.util.Base64

/**
 * The camera logins against known answers:
 * - SPAKE2+: the RFC 9383 P-256 test vector (as the tapo crate's tpap_spake2p.rs uses it), its w0/w1 derivation
 *   vector, and an end-to-end exchange computed with pytapo's own spake2p.py (fixed randoms).
 * - SHA-256-crypt: the specification's vectors and the C120 credential from the tapo crate.
 * - AES-CCM: the tapo crate's tpap_cipher.rs known answer, and pytapo's session with Python's AESCCM.
 * - encrypt_type 3: values computed with python-kasa's SslAesTransport functions for password "hunter2".
 * - securePassthrough: python-kasa's AesTransport.hash_credentials for user@example.com / hunter2.
 */
class CameraCryptoTest {
    private fun s(hex: String) = BigInteger(hex, 16)

    @Test fun spake2pMatchesTheRfc9383Vector() {
        val proof = Spake2p.prove(
            s("bb8e1bbcf3c48f62c08db243652ae55d3e5586053fca77102994f23ad95491b3"),
            s("7e945f34d78785b8a3ef44d0df5a1a97d6b3b460409a345ca7830387a74b1dba"),
            s("d1232c8e8693d02368976c174e2088851b8365d0d79a9eee709c6a05a2fad539"),
            "SPAKE2+-P256-SHA256-HKDF-SHA256-HMAC-SHA256 Test Vectors".toByteArray(),
            "client".toByteArray(),
            "server".toByteArray(),
            ("04c0f65da0d11927bdf5d560c69e1d7d939a05b0e88291887d679fcadea75810fb" +
                "5cc1ca7494db39e82ff2f50665255d76173e09986ab46742c798a9a68437b048").unhex(),
        )!!
        assertEquals(
            "04ef3bd051bf78a2234ec0df197f7828060fe9856503579bb1733009042c15c0c1" +
                "de127727f418b5966afadfdd95a6e4591d171056b333dab97a79c7193e341727",
            proof.share.hex(),
        )
        assertEquals("926cc713504b9b4d76c9162ded04b5493e89109f6d89462cd33adc46fda27527", proof.confirm.hex())
        assertEquals("0c5f8ccd1413423a54f6c1fb26ff01534a87f893779c6e68666d772bfd91f3e7", proof.sharedKey.hex())
        assertTrue(proof.verify("9747bcc4f8fe9f63defee53ac9b07876d907d55047e6ff2def2e7529089d3e68".unhex()))
        assertFalse(proof.verify(proof.confirm))
    }

    @Test fun w0w1ReduceEachHalfOfPbkdf2() {
        val (w0, w1) = Spake2p.w0w1("f3bbbd66a63d4bf1747940578ec3d0103530e21d", "0123456789abcdef".toByteArray(), 3000)
        assertEquals("f01588c03f935783a7c530a83ab1ade1a3261b13005521b6946dc5c7ad259f2c", P256.fixed(w0).hex())
        assertEquals("de9bd4a17ba3b23e2a26fbf5ae94010c68f5e0e34388fe6ec227fcb45bd0b42e", P256.fixed(w1).hex())
    }

    @Test fun badSharesStopTheLogin() {
        val notOnCurve = byteArrayOf(4) + ByteArray(64) { 1 }
        assertNull(Spake2p.prove(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN, ByteArray(32), ByteArray(0), ByteArray(0), notOnCurve))
        // A share of w0·N cancels the password and leaves the point at infinity.
        val (w0, w1) = Spake2p.w0w1("credential", ByteArray(16), 1)
        val n = P256.decode("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49".unhex())!!
        val share = P256.encode(P256.mul(w0, n)!!)
        assertNull(Spake2p.prove(w0, w1, BigInteger.TEN, ByteArray(32), ByteArray(0), ByteArray(0), share))
        assertNull(P256.decode(ByteArray(10)))
    }

    /** One whole TPAP exchange as pytapo computes it (Spake2pClient with random_scalar 12345 → x = 12346). */
    @Test fun tpapExchangeMatchesPytapo() {
        val secret = CameraSecret.of("hunter2")
        val extra = Json.parseToJsonElement("""{"type":"password_shadow","params":{"passwd_id":5,"passwd_prefix":"${'$'}5${'$'}SanLv2iJ5eS/8F85${'$'}"}}""").jsonObject
        val credential = CameraLogin.tpapCredential(CameraLogin.tpapPasscodes(secret)[0], extra)
        assertEquals("\$5\$SanLv2iJ5eS/8F85\$knSHifj.eymprrT5pR/vHECT/yy4QYlHPBlufMkQEB7", credential)
        val (w0, w1) = Spake2p.w0w1(credential, ByteArray(16) { it.toByte() }, 1000)
        val devShare = P256.encode(P256.mul(BigInteger.valueOf(7), P256.G)!!)
        val proof = Spake2p.prove(w0, w1, BigInteger.valueOf(12346), Spake2p.context(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }), ByteArray(0), ByteArray(0), devShare)!!
        assertEquals(
            "042c517d83a6fdc2d18df72cef9ec203b340a2c4167feefd63930b82a457fa9776604f35c722cfa52a8c218896307e9c91131fd0daae7f012365846a326075dd08",
            proof.share.hex(),
        )
        assertEquals("d20b2ab0acbb942ced659e8ee258f74bbdd4ba1e89a494f45eeb10942a8b48ee", proof.confirm.hex())
        assertEquals("85a58bb26844e99cd49d243c5debf2de59e0f607cb16a7d52b72bda5ea773f84", proof.sharedKey.hex())
        val session = TpapSession(proof.sharedKey, 5)
        assertEquals("bf8e794e4a57479b8ede34579ff78c90", session.key.hex())
        assertEquals("b2a8e52de17fe8c6" + "00000005", session.nonce(5).hex())
        val (seq, body) = session.encrypt("""{"method":"multipleRequest"}""".toByteArray())
        assertEquals(5, seq)
        assertEquals("00000005" + "eb77a077d9b3af24b29bc3d8df20657ef2b608d57e8e274662e5301ad4e8fc275e40532fce249e65641bd120", body.hex())
        assertEquals(6, session.seq)
    }

    @Test fun ccmMatchesTheTapoCrateKnownAnswer() {
        val session = TpapSession(ByteArray(32) { it.toByte() }, 0x01020304)
        val msg = """{"method":"get_device_info"}"""
        val (seq, body) = session.encrypt(msg.toByteArray())
        assertEquals(0x01020304, seq)
        assertEquals(
            "01020304c0331c1c36a5df4ce0c68e5a27b2667ebf842dc994a3fd24b9e860a60fab76d4b66b9b669d95cca10c012f13",
            body.hex(),
        )
        assertEquals(msg, session.decrypt(seq, body).toString(Charsets.UTF_8))
        // A reply under another number, or a changed byte, is refused.
        try { session.decrypt(seq + 1, body); fail() } catch (_: java.security.GeneralSecurityException) {}
        val bad = body.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        try { session.decrypt(seq, bad); fail() } catch (_: java.security.GeneralSecurityException) {}
        try { session.decrypt(0, ByteArray(19)); fail() } catch (_: java.security.GeneralSecurityException) {}
    }

    @Test fun sha256CryptVectors() {
        assertEquals("\$5\$saltstring\$5B8vYYiY.CVt1RlTTf8KbXBH3hsxY/GNooZaBBGWEc5", TpapCrypto.sha256Crypt("Hello world!".toByteArray(), "saltstring", null))
        assertEquals(
            "\$5\$rounds=10000\$saltstringsaltst\$3xv.VbSHBb41AL9AvLeujZkZRBAwqFMz2.opqey6IcA",
            TpapCrypto.sha256Crypt("Hello world!".toByteArray(), "saltstringsaltstring", 10000),
        )
        assertEquals("\$5\$SanLv2iJ5eS/8F85\$bpyBLtYAtKvh8tnNopK6r.tH3KYe39JLToe4minczu6", TpapCrypto.sha256Crypt("hunter2".toByteArray(), "SanLv2iJ5eS/8F85", 5000))
        assertEquals("saltstring" to 10000, TpapCrypto.parseCryptPrefix("\$5\$rounds=10000\$saltstring\$"))
        assertEquals("abc" to null, TpapCrypto.parseCryptPrefix("\$5\$abc\$"))
        assertNull(TpapCrypto.parseCryptPrefix("\$6\$abc\$"))
    }

    @Test fun tpapCredentialTransforms() {
        val pc = "2ab96390c7dbe3439de74d0c9b0b1767"
        assertEquals(pc, CameraLogin.tpapCredential(pc, null))
        val sha1 = Json.parseToJsonElement("""{"type":"password_shadow","params":{"passwd_id":2}}""").jsonObject
        assertEquals("f3bbbd66a63d4bf1747940578ec3d0103530e21d", CameraLogin.tpapCredential("hunter2", sha1))
        for (bad in listOf("""{"type":"password_shadow","params":{"passwd_id":3}}""", """{"type":"other"}""", """{"type":"password_shadow","params":{"passwd_id":5}}""")) {
            try {
                CameraLogin.tpapCredential(pc, Json.parseToJsonElement(bad).jsonObject)
                fail(bad)
            } catch (_: CameraLogin.UnsupportedTransform) {
            }
        }
        val secret = CameraSecret.of("hunter2")
        assertEquals(listOf("2ab96390c7dbe3439de74d0c9b0b1767", "F52FBD32B2B3B86FF88EF6C490628285F482AF15DDCB29541F94BCF526A3F6C7"), CameraLogin.tpapPasscodes(secret))
        assertEquals("21232f297a57a5a743894a0e4a801fc3", CameraLogin.tpapUser(null))
        assertEquals("8C6976E5B5410415BDE908BD4DEE15DFB167A9C873FC4BB8A81F6F2AB448A918", CameraLogin.tpapUser(1))
    }

    @Test fun secureLoginMatchesPythonKasa() {
        val secret = CameraSecret.of("hunter2")
        val cn = "AAAA1111BBBB2222"
        val sn = "CCCC3333DDDD4444"
        assertEquals("F52FBD32B2B3B86FF88EF6C490628285F482AF15DDCB29541F94BCF526A3F6C7", secret.sha256Upper)
        assertEquals("2AB96390C7DBE3439DE74D0C9B0B1767", secret.md5Upper)
        val confirm = "F3469D3490DC4E6275104C529BDD8064CC5B1D1621A79A497F7C57E9C1BEF0B3CCCC3333DDDD4444AAAA1111BBBB2222"
        assertEquals(confirm, CameraLogin.deviceConfirm(cn, sn, secret.sha256Upper))
        assertEquals(secret.sha256Upper, CameraLogin.matchConfirm(secret, cn, sn, confirm))
        assertEquals(secret.md5Upper, CameraLogin.matchConfirm(secret, cn, sn, CameraLogin.deviceConfirm(cn, sn, secret.md5Upper)))
        // A camera on another password: no match, so the digest is never sent.
        assertNull(CameraLogin.matchConfirm(CameraSecret.of("other"), cn, sn, confirm))
        assertEquals(
            "032AEA43C2E3C5A43FA7A31F66C9227C0EDF125CD32513BA957392956187FB1EAAAA1111BBBB2222CCCC3333DDDD4444",
            CameraLogin.digestPasswd(cn, sn, secret.sha256Upper),
        )
        val session = CameraAesSession(cn, sn, secret.sha256Upper, 100)
        assertEquals("2f8a3d0c48b10755db821635dff6e1c3", session.key.hex())
        assertEquals("254423ec9e305972bf73d05f9c7ac6ed", session.iv.hex())
        assertEquals("BF3640E38D8354EA1AFA20B06ADABE0B9C9BFD61E3C2148ABEAF8150A19435E2", session.tag("""{"method":"test"}""", 100))
        assertEquals("Fe5qv9CghrG7SkBHA0USO+7zG66+m5F8qtf7nXw2Hys=", session.encrypt("""{"method":"multipleRequest"}"""))
        assertEquals("""{"method":"multipleRequest"}""", session.decrypt("Fe5qv9CghrG7SkBHA0USO+7zG66+m5F8qtf7nXw2Hys="))
        assertEquals(100, session.nextSeq())
        assertEquals(101, session.nextSeq())
    }

    @Test fun passthroughLoginMatchesPythonKasa() {
        val keep = LegacySecret.of("user@example.com", "hunter2", keepPassword = true)
        val v1 = PassthroughCrypto.loginParams(keep, v2 = false)!!
        assertEquals("""{"password":"aHVudGVyMg==","username":"NjNhNzEwNTY5MjYxYTI0YjM3NjYyNzViNzAwMGNlOGQ3YjMyZTJmNw=="}""", v1.toString())
        val v2 = PassthroughCrypto.loginParams(keep, v2 = true)!!
        assertEquals(
            """{"password2":"ZjNiYmJkNjZhNjNkNGJmMTc0Nzk0MDU3OGVjM2QwMTAzNTMwZTIxZA==","username":"NjNhNzEwNTY5MjYxYTI0YjM3NjYyNzViNzAwMGNlOGQ3YjMyZTJmNw=="}""",
            v2.toString(),
        )
        // Without the kept password, version 1 can't be sent at all, and version 2 still works.
        val hashesOnly = LegacySecret.of("user@example.com", "hunter2", keepPassword = false)
        assertNull(hashesOnly.password)
        assertNull(PassthroughCrypto.loginParams(hashesOnly, v2 = false))
        assertEquals(v2, PassthroughCrypto.loginParams(hashesOnly, v2 = true))
    }

    @Test fun passthroughHandshakeRoundTrip() {
        val kp = PassthroughCrypto.newKeyPair()
        val body = PassthroughCrypto.handshakeBody(kp)
        // 1024-bit key: the 314-byte body python-kasa sends with its fixed Content-Length.
        assertEquals(314, body.toByteArray().size)
        assertTrue(body.startsWith("""{"method":"handshake","params":{"key":"-----BEGIN PUBLIC KEY-----\n"""))
        val key = ByteArray(16) { 1 }
        val iv = ByteArray(16) { 2 }
        val c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, kp.public)
        val (k, i) = PassthroughCrypto.sessionKey(kp.private, Base64.getEncoder().encodeToString(c.doFinal(key + iv)))
        assertArrayEquals(key, k)
        assertArrayEquals(iv, i)
        val enc = AesCbc.encrypt(k, i, "hello".toByteArray())
        assertEquals("hello", AesCbc.decrypt(k, i, enc).toString(Charsets.UTF_8))
    }
}
