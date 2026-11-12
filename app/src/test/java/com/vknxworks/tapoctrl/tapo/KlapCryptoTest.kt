package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import com.vknxworks.tapoctrl.net.LanAddress

/**
 * Fixed vectors computed independently with .NET's SHA/MD5/AES (a separate script, not this code) for
 * email "user@example.com", password "hunter2", localSeed 00..0f and remoteSeed 10..1f.
 */
class KlapCryptoTest {
    private val email = "user@example.com"
    private val password = "hunter2"
    private val local = ByteArray(16) { it.toByte() }
    private val remote = ByteArray(16) { (it + 16).toByte() }

    @Test fun authHashes() {
        assertEquals("b49b2da16ee8155335c944a908c08fb4d18ea952ca0f73b60c8f77d08642e781", KlapCrypto.authHashV2(email, password).hex())
        assertEquals("c365c2d8159d185d19139d3d3ef3d6db", KlapCrypto.authHashV1(email, password).hex())
    }

    @Test fun handshakeHashesV2() {
        val auth = AuthHash(KlapVersion.V2, KlapCrypto.authHashV2(email, password))
        assertEquals("f55f1932e0bebe2e755839cf79f0ac918e136ae7038f9effbe21bf1285b3a1c2", KlapCrypto.serverHash(auth, local, remote).hex())
        assertEquals("94bdb61c3b7f1fbe093dfc783d8dd91f8fb9141d0f99ccad01f0831c1c3dbcbe", KlapCrypto.clientHash(auth, local, remote).hex())
    }

    @Test fun handshakeHashesV1() {
        val auth = AuthHash(KlapVersion.V1, KlapCrypto.authHashV1(email, password))
        assertEquals("f38b5a8f75dc99643ed238d557b330563c40e6f4e9381e1ea7c808781e7721e9", KlapCrypto.serverHash(auth, local, remote).hex())
        assertEquals("896a5d90b80b90f109801e2744a67c5da313fb7e1b124cf991dab8406fe4b960", KlapCrypto.clientHash(auth, local, remote).hex())
    }

    @Test fun matchPicksTheRightVersion() {
        val candidates = KlapCrypto.authHashes(email, password)
        val v1Server = "f38b5a8f75dc99643ed238d557b330563c40e6f4e9381e1ea7c808781e7721e9".unhex()
        val v2Server = "f55f1932e0bebe2e755839cf79f0ac918e136ae7038f9effbe21bf1285b3a1c2".unhex()
        assertSame(candidates[1], KlapCrypto.match(candidates, local, remote, v1Server))
        assertSame(candidates[0], KlapCrypto.match(candidates, local, remote, v2Server))
        assertNull(KlapCrypto.match(KlapCrypto.authHashes(email, "wrong"), local, remote, v2Server))
    }

    @Test fun sessionDerivation() {
        val s = KlapSession(local, remote, KlapCrypto.authHashV2(email, password))
        assertEquals("bdaeeb0da10915fe4267dfdaa22a3586", s.key.hex())
        assertEquals("1cc7a7e88ef0916075821790", s.ivBase.hex())
        assertEquals(-1214602357, s.seq)
        assertEquals("419634a6c004241f729c2344210d6dab5557bbff9de2d4fb41bbb189", s.sig.hex())
    }

    @Test fun encryptRequest() {
        val s = KlapSession(local, remote, KlapCrypto.authHashV2(email, password))
        val (seq, body) = s.encrypt("""{"method":"get_device_info"}""".toByteArray())
        assertEquals(-1214602356, seq)
        assertEquals(
            "4a9557ab0c4032934d2492030180a48b954b109b6c3b06921cc71ad321c2cd9d" +
                "1b303cb7964666646cc7b197a94ec8b5022041706736544b70a31400646797cb",
            body.hex(),
        )
    }

    @Test fun decryptResponse() {
        val s = KlapSession(local, remote, KlapCrypto.authHashV2(email, password))
        val body = ("0000000000000000000000000000000000000000000000000000000000000000" +
            "be9010eb9d32817e6f67fcd3061326525b2f0b8c968a1cc6b80f3caf81ddf06b9bbffd1a4a3f283a0954b55617289bb1").unhex()
        assertEquals("""{"error_code":0,"result":{"device_on":true}}""", s.decrypt(-1214602356, body).toString(Charsets.UTF_8))
    }

    @Test fun roundTripAndWrap() {
        val s = KlapSession(local, remote, ByteArray(32) { 7 })
        repeat(3) {
            val msg = """{"method":"set_device_info","params":{"device_on":true}}""".toByteArray()
            val (seq, body) = s.encrypt(msg)
            assertArrayEquals(msg, s.decrypt(seq, body))
        }
        // The counter is a signed 32-bit int and wraps like the device's.
        assertEquals(Int.MIN_VALUE, Int.MAX_VALUE + 1)
    }

    @Test fun cookie() {
        val (id, timeout) = KlapClient.parseCookie(listOf("TP_SESSIONID=ABCDEF0123;TIMEOUT=86400"))
        assertEquals("TP_SESSIONID=ABCDEF0123", id)
        assertEquals(86400L, timeout)
        assertEquals(null to null, KlapClient.parseCookie(emptyList()))
    }

    @Test fun discoveryPacket() {
        val pkt = Discovery.discoveryPacket("KEY", 0x12345678)
        val payload = """{"params":{"rsa_key":"KEY"}}"""
        assertEquals(16 + payload.length, pkt.size)
        assertEquals("02000001001c11001234567810a1deac", pkt.copyOf(16).hex())
        assertEquals(payload, pkt.copyOfRange(16, pkt.size).toString(Charsets.UTF_8))
    }

    @Test fun discoveryReply() {
        val json = """{"result":{"device_type":"SMART.TAPOPLUG","device_model":"P110(EU)","ip":"8.8.8.8","mac":"AA-BB-CC-DD-EE-FF",
            "mgt_encrypt_schm":{"encrypt_type":"KLAP","http_port":80}},"error_code":0}"""
        val f = Discovery.parseDiscoveryReply(ByteArray(16) + json.toByteArray(), "192.168.1.30")!!
        assertEquals("192.168.1.30", f.ip) // never the address the reply claims
        assertEquals("P110(EU)", f.model)
        assertFalse(f.legacy)
        assertNull(Discovery.parseDiscoveryReply(ByteArray(10), "192.168.1.30"))
    }

    @Test fun deviceInfo() {
        val r = Json.parseToJsonElement(
            """{"device_id":"x","model":"L530","type":"SMART.TAPOBULB","nickname":"${DeviceInfo.toB64("Desk lamp")}",
              "fw_ver":"1.1.0 Build 230101","hw_ver":"2.0","mac":"AA-BB","rssi":-50,"signal_level":3,"device_on":true,
              "brightness":40,"color_temp":2700,"hue":120,"saturation":80,"color_temp_range":[2500,6500]}""",
        )
        val i = DeviceInfo.parse(r)
        assertEquals("Desk lamp", i.nickname)
        assertTrue(i.isLight && i.hasBrightness && i.hasColorTemp && i.hasColor)
        assertEquals(DeviceKind.BULB, i.kind)
        assertEquals(2500..6500, i.colorTempRange)
        val e = EnergyUsage.parse(Json.parseToJsonElement("""{"today_energy":120,"month_energy":4500,"current_power":15300}"""))
        assertEquals(15.3, e.powerW!!, 1e-9)
        assertEquals(4500.0, e.monthWh!!, 1e-9)
    }

    @Test fun lanOnly() {
        assertTrue(LanAddress.parse("192.168.1.20") != null)
        assertTrue(LanAddress.parse("10.0.0.5") != null)
        assertTrue(LanAddress.parse("172.16.4.1") != null)
        assertTrue(LanAddress.parse("169.254.10.10") != null)
        assertNull(LanAddress.parse("172.32.0.1"))
        assertNull(LanAddress.parse("8.8.8.8"))
        assertNull(LanAddress.parse("example.com"))
        assertNull(LanAddress.parse("192.168.1.256"))
        assertTrue(LanAddress.isPrivate(InetAddress.getByName("fe80::1")))
        assertTrue(LanAddress.isPrivate(InetAddress.getByName("fd00::1")))
        assertFalse(LanAddress.isPrivate(InetAddress.getByName("2001:db8::1")))
    }
}
