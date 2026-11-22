package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The Tapo C200 from discovery to the TPAP credential, with recorded replies:
 * - python-kasa tests/fixtures/smartcam/C200_1.0_1.3.17.json (discovery_result; encrypt_type 3 firmware),
 * - the tapo crate's tests/fixtures/smartcam/C220_1.0_1.4.4_TPAP.json (discovery_response) and
 *   C120_1.0_1.9.4_TPAP_SHA-256-crypt.json (a pake_register extra_crypt without a salt),
 * - the extra_crypt a C200 hw 5.0 on firmware 1.4.6 sends (pytapo transport/tpap, freeKC tapo-v4-protocol §4.5);
 *   expected credentials computed with pytapo's spake2p.py, MD5-crypt with `openssl passwd -1`.
 */
class CameraC200Test {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun udp(s: String) = ByteArray(16) + s.toByteArray()

    @Test fun c200DiscoveryIsACamera() {
        val reply = """{"error_code": 0, "result": {"decrypted_data": {"connect_ssid": "#MASKED_SSID#", "connect_type": "wireless", "device_id": "0000000000000000000000000000000000000000", "http_port": 443, "last_alarm_time": "1789446359", "last_alarm_type": "motion", "owner": "00000000000000000000000000000000", "sd_status": "offline"}, "device_id": "00000000000000000000000000000000", "device_model": "C200", "device_name": "#MASKED_NAME#", "device_type": "SMART.IPCAMERA", "encrypt_info": {"data": "", "key": "", "sym_schm": "AES"}, "encrypt_type": ["3"], "factory_default": false, "firmware_version": "1.3.17 Build 260112 Rel.54862n(4555)", "hardware_version": "1.0", "ip": "127.0.0.123", "is_support_iot_cloud": true, "mac": "28-EE-52-00-00-00", "mgt_encrypt_schm": {"is_support_https": true}, "protocol_version": 1}}"""
        val f = Discovery.parseDiscoveryReply(udp(reply), "192.168.1.40")!!
        assertEquals("192.168.1.40", f.ip) // never the 127.0.0.123 it claims
        assertEquals("C200", f.model)
        assertEquals("SMART.IPCAMERA", f.type)
        assertEquals("28-EE-52-00-00-00", f.mac)
        assertEquals(true, f.https)
        assertTrue(f.camera)
        assertTrue(!f.legacy)
        assertNull(f.encryption)
    }

    @Test fun tpapCameraDiscoveryIsACamera() {
        val reply = """{"error_code": 0, "result": {"device_id": "0123456789ABCDEF0123456789ABCDEF", "device_model": "C220", "device_name": "Simulated Camera", "device_type": "SMART.IPCAMERA", "encrypt_info": {"data": "U2ltdWxhdGVkIGRhdGE=", "key": "U2ltdWxhdGVkIGtleQ==", "sym_schm": "AES"}, "encrypt_type": ["4"], "factory_default": false, "firmware_version": "1.4.4 Build 260515 Rel.24570n", "hardware_version": "1.0", "ip": "192.168.1.100", "isResetWiFi": false, "is_support_iot_cloud": true, "mac": "AA-BB-CC-DD-EE-FF", "mgt_encrypt_schm": {"is_support_https": true}, "protocol_version": 1, "sv": 1, "tpap": {"noc": 1, "pake": [2], "port": 443, "tls": 1}}}"""
        val f = Discovery.parseDiscoveryReply(udp(reply), "192.168.1.41")!!
        assertTrue(f.camera)
        assertTrue(!f.legacy)
        // A KLAP probe answer for the same address doesn't turn it into a plug.
        val merged = Found("192.168.1.41", encryption = "KLAP").mergedWith(f)
        assertEquals("C220", merged.model)
        assertTrue(merged.camera)
    }

    private fun shadow(id: Any, prefix: String? = null, salt: String? = null, rounds: Int? = null) = buildJsonObject {
        put("type", "password_shadow")
        putJsonObject("params") {
            when (id) { is Int -> put("passwd_id", id); else -> put("passwd_id", id.toString()) }
            prefix?.let { put("passwd_prefix", it) }
            salt?.let { put("passwd_salt", it) }
            rounds?.let { put("passwd_rounds", it) }
        }
    }

    private val c200Passcode = CameraLogin.tpapPasscodes(CameraSecret.of("hunter2"))[1]
    private val c200Credential = "\$5\$x1hYMevsEYq2APg+\$hXg3nMLS1309nBgL6UsSKBeOl0uJk3zvi6OcrDXKEeC"

    @Test fun c200Firmware146Credential() {
        assertEquals("F52FBD32B2B3B86FF88EF6C490628285F482AF15DDCB29541F94BCF526A3F6C7", c200Passcode)
        // As the C200 sends it: "$5$<salt>$", plus passwd_rounds 5000 (pytapo's fake camera).
        assertEquals(c200Credential, CameraLogin.tpapCredential(c200Passcode, shadow(5, prefix = "\$5\$x1hYMevsEYq2APg+\$", rounds = 5000)))
        // passwd_id as text, a prefix without the "$5$" marker (pytapo takes it as the salt), and rounds named.
        assertEquals(c200Credential, CameraLogin.tpapCredential(c200Passcode, shadow("5", prefix = "x1hYMevsEYq2APg+")))
        assertEquals(
            "\$5\$rounds=5000\$x1hYMevsEYq2APg+\$hXg3nMLS1309nBgL6UsSKBeOl0uJk3zvi6OcrDXKEeC",
            CameraLogin.tpapCredential(c200Passcode, shadow(5, prefix = "\$5\$rounds=5000\$x1hYMevsEYq2APg+\$")),
        )
        // Only passwd_salt (the tapo crate's C120 reply).
        assertEquals(c200Credential, CameraLogin.tpapCredential(c200Passcode, shadow(5, salt = "x1hYMevsEYq2APg+", rounds = 5000)))
    }

    @Test fun md5CryptMatchesOpenssl() {
        assertEquals("\$1\$saltstri\$YMyguxXMBpd2TEZ.vS/3q1", TpapCrypto.md5Crypt("Hello world!".toByteArray(), "saltstring"))
        assertEquals("\$1\$abcdefgh\$vhxKZ/s1ygZHyCEDPyqtQ/", CameraLogin.tpapCredential("hunter2", shadow(1, prefix = "\$1\$abcdefgh\$")))
    }

    @Test fun unsupportedTransformsSayWhatWasAsked() {
        // tapo crate C120 1.9.4 TPAP SHA-256-crypt fixture: passwd_id 5 with neither a prefix nor a salt.
        val c120 = json("""{"type":"password_shadow","params":{"passwd_id":5,"passwd_rounds":5000}}""")
        val cases = mapOf(
            c120 to listOf("password_shadow", "passwd_id=5", "passwd_rounds", "no salt"),
            json("""{"type":"password_shadow","params":{"passwd_id":3}}""") to listOf("passwd_id=3"),
            json("""{"type":"password_authkey","params":{"authkey_tmpkey":"secret-ish","authkey_dictionary":"abc"}}""") to listOf("password_authkey", "authkey_tmpkey"),
        )
        for ((extra, words) in cases) {
            try {
                CameraLogin.tpapCredential(c200Passcode, extra)
                fail("$extra")
            } catch (e: CameraLogin.UnsupportedTransform) {
                for (w in words) assertTrue("${e.what} should name $w", e.what.contains(w))
                // Names only: values (salts, keys) stay out of the message.
                assertTrue(e.what, !e.what.contains("secret-ish"))
            }
        }
    }

    private fun b64(n: Int) = Base64.getEncoder().encodeToString(ByteArray(n) { it.toByte() })

    @Test fun pakeRegisterIsCheckedBeforeAnythingIsSent() {
        val point = P256.encode(P256.G)
        val ok = buildJsonObject {
            put("dev_salt", b64(16)); put("dev_share", Base64.getEncoder().encodeToString(point)); put("dev_random", b64(32))
            put("iterations", 5000); put("cipher_suites", 1); put("encryption", "aes_128_ccm")
            put("extra_crypt", shadow(5, prefix = "\$5\$x1hYMevsEYq2APg+\$", rounds = 5000))
        }
        val r = CameraLogin.tpapRegistration(ok)
        assertEquals(5000, r.iterations)
        assertEquals(65, r.deviceShare.size)
        assertEquals(c200Credential, CameraLogin.tpapCredential(c200Passcode, r.extraCrypt))
        // No iterations: the Tapo app's default.
        val noIterations = JsonObject(ok - "iterations")
        assertEquals(CameraLogin.DEFAULT_ITERATIONS, CameraLogin.tpapRegistration(noIterations).iterations)
        assertEquals(5000, CameraLogin.tpapRegistration(JsonObject(ok + ("iterations" to Json.parseToJsonElement("\"5000\"")))).iterations)
        val bad = mapOf(
            JsonObject(ok - "dev_share") to "no dev_share",
            JsonObject(ok + ("iterations" to Json.parseToJsonElement("200000"))) to "iterations=200000",
            JsonObject(ok + ("cipher_suites" to Json.parseToJsonElement("2"))) to "cipher_suites=2",
            JsonObject(ok + ("encryption" to Json.parseToJsonElement("\"aes_256_gcm\""))) to "encryption=aes_256_gcm",
            JsonObject(ok + ("dev_salt" to Json.parseToJsonElement("\"not base64!\""))) to "dev_salt isn't Base64",
        )
        for ((reg, words) in bad) {
            try {
                CameraLogin.tpapRegistration(reg)
                fail(words)
            } catch (e: CameraLogin.UnsupportedTransform) {
                assertTrue("${e.what} should say $words", e.what.contains(words))
            }
        }
    }
}
