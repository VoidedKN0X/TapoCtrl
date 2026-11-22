package com.vknxworks.tapoctrl.tapo

import com.vknxworks.tapoctrl.net.LanAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

/** "Scan a network range": what may be scanned, and that scanning stays read-only and local. */
class ScanRangeTest {
    private fun ok(s: String) = (ScanRange.parse(s) as ScanRange.Parsed.Ok).range

    @Test fun rangesAreNormalisedAndCounted() {
        val r = ok("192.168.1.0/24")
        assertEquals("192.168.1.0/24", r.text)
        assertEquals(254, r.size)
        assertEquals("192.168.1.1", LanAddress.text(r.hosts().first()))
        assertEquals("192.168.1.254", LanAddress.text(r.hosts().last()))
        assertEquals("192.168.1.0/24", ok(" 192.168.1.77/24 ").text)
        assertEquals(1022, ok("10.0.4.0/22").size)
        assertEquals("10.0.4.0/22", ok("10.0.5.9/22").text)
        assertEquals(1, ok("172.16.0.9/32").size)
        assertEquals(2, ok("172.31.255.254/31").size)
        assertEquals(254, ok("100.64.0.0/24").size)
        assertEquals(254, ok("100.127.3.0/24").size)
    }

    @Test fun onlySmallPrivateRanges() {
        for (s in listOf("192.168.0.0/21", "10.0.0.0/8", "192.168.0.0/16")) assertEquals(s, ScanRange.Parsed.TooLarge, ScanRange.parse(s))
        for (s in listOf("8.8.8.0/24", "172.32.0.0/24", "100.128.0.0/24", "100.63.255.0/24", "169.254.1.0/24", "127.0.0.0/24", "11.0.0.0/24")) {
            assertEquals(s, ScanRange.Parsed.NotPrivate, ScanRange.parse(s))
        }
        for (s in listOf("", "192.168.1.0", "192.168.1.0/", "192.168.1/24", "192.168.1.300/24", "192.168.1.0/33", "abc", "192.168.1.0/24/1")) {
            assertEquals(s, ScanRange.Parsed.Invalid, ScanRange.parse(s))
        }
        // Every address of an accepted range passes the check every connection goes through.
        for (s in listOf("192.168.3.0/22", "10.255.252.0/22", "172.16.0.0/22", "100.64.0.0/22")) assertTrue(ok(s).hosts().all { LanAddress.isPrivate(it) })
    }

    @Test fun sharedAddressSpaceIsLocal() {
        assertTrue(LanAddress.parse("100.64.0.1") != null)
        assertTrue(LanAddress.parse("100.127.255.254") != null)
        assertTrue(LanAddress.parse("100.63.0.1") == null)
        assertTrue(LanAddress.parse("100.128.0.1") == null)
        assertFalse(LanAddress.isPrivate(InetAddress.getByName("1.1.1.1")))
    }

    @Test fun wifiSubnetIsTheSameAsBefore() {
        val me = LanAddress.parse("192.168.1.23")!!
        val targets = Discovery.subnet(me)
        assertEquals(253, targets.size)
        assertFalse(targets.contains(me))
        assertEquals("192.168.1.255", LanAddress.text(Discovery.broadcastOf(me)))
    }

    /** Scanning (Wi-Fi or range) sends only the discovery packet and the KLAP probe: never a device method or a login. */
    @Test fun scanningOnlyReads() {
        val src = File("src/main/java/com/vknxworks/tapoctrl")
        val discovery = File(src, "tapo/Discovery.kt").readText()
        for (forbidden in listOf(".call(", "CameraClient", "PinnedTls", "KlapClient(", "SmartClient", "PassthroughClient", "handshake2", "TapoMethods", "AccountParts", "AccountVault", "AuthHash", "CameraSecret")) {
            assertFalse("Discovery.kt uses $forbidden", discovery.contains(forbidden))
        }
        // The only HTTP request: handshake1 with a random seed.
        assertEquals(listOf("/app/handshake1"), Regex("""LanHttp\.post\(ip, "([^"]+)"""").findAll(discovery).map { it.groupValues[1] }.toList())
        // The discovery packet carries only the throwaway RSA key.
        val payload = Discovery.discoveryPacket("KEY", 1).copyOfRange(16, Discovery.discoveryPacket("KEY", 1).size).toString(Charsets.UTF_8)
        assertEquals("""{"params":{"rsa_key":"KEY"}}""", payload)
        // Reading the phone's networks sends nothing at all (and needs no Wi-Fi name or location).
        val local = File(src, "net/LocalNetwork.kt").readText()
        for (forbidden in listOf("Socket", "openConnection", "LanHttp", "post(", "WifiManager", "ssid", "SSID", "bindProcessToNetwork", "bindSocket")) {
            assertFalse("LocalNetwork.kt uses $forbidden", local.contains(forbidden))
        }
        // The add screen's scan goes through Discovery only, then moves known devices by MAC (no device is asked anything else).
        val add = File(src, "ui/devices/AddDeviceScreen.kt").readText()
        val scanFn = add.substringAfter("fun startScan(").substringBefore("fun scanWifi(")
        assertTrue(scanFn.contains("Discovery.scan("))
        for (forbidden in listOf("c.devices.add(", "addCamera(", "refresh(", ".call(")) assertFalse("startScan uses $forbidden", scanFn.contains(forbidden))
        // No new permission: no location, Wi-Fi state or nearby-devices permission (CI checks the APK too).
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (p in listOf("LOCATION", "NEARBY_WIFI_DEVICES", "CHANGE_NETWORK_STATE", "CHANGE_WIFI")) assertFalse(p, manifest.contains(p))
        assertTrue(manifest.contains("android.permission.ACCESS_WIFI_STATE\" tools:node=\"remove\""))
    }
}
