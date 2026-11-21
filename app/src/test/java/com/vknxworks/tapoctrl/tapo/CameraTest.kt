package com.vknxworks.tapoctrl.tapo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

/** Camera request shapes (as python-kasa, pytapo and the tapo crate send them), reply parsing and the client's guards. */
class CameraTest {
    private fun j(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun requestsMatchTheOpenSourceProjects() {
        assertEquals("""{"device_info":{"name":["basic_info"]}}""", Camera.deviceInfoParams().toString())
        assertEquals("""{"lens_mask":{"name":["lens_mask_info"]}}""", Camera.lensMaskParams().toString())
        assertEquals("""{"lens_mask":{"lens_mask_info":{"enabled":"on"}}}""", Camera.setLensMaskParams(true).toString())
        assertEquals("""{"lens_mask":{"lens_mask_info":{"enabled":"off"}}}""", Camera.setLensMaskParams(false).toString())
        assertEquals("""{"motor":{"move":{"x_coord":"10","y_coord":"-5"}}}""", Camera.moveParams(10, -5).toString())
        assertEquals("""{"preset":{"goto_preset":{"id":"1"}}}""", Camera.gotoPresetParams("1").toString())
        assertEquals("""{"preset":{"name":["preset"]}}""", Camera.presetsParams().toString())
        assertEquals("""{"app_component":{"name":"app_component_list"}}""", Camera.componentsParams().toString())
        assertEquals("""{"system":{"reboot":"null"}}""", Camera.rebootParams().toString())
    }

    /** The C200 1.0 / 1.3.17 replies from python-kasa's test fixture. */
    @Test fun parsesTheC200Fixture() {
        val info = Camera.parseInfo(j("""{"device_info":{"basic_info":{"device_alias":"#MASKED_NAME#","device_model":"C200","device_type":"SMART.IPCAMERA","hw_version":"1.0","mac":"28-EE-52-00-00-00","sw_version":"1.3.17 Build 260112 Rel.54862n(4555)"}}}"""))
        assertEquals("C200", info.model)
        assertEquals("1.3.17 Build 260112 Rel.54862n(4555)", info.fwVersion)
        assertEquals("28-EE-52-00-00-00", info.mac)
        assertEquals(false, Camera.parseLensMask(j("""{"lens_mask":{"lens_mask_info":{".name":"lens_mask_info",".type":"lens_mask_info","enabled":"off"}}}""")))
        assertNull(Camera.parseLensMask(j("{}")))
        assertEquals(emptyList<CameraPreset>(), Camera.parsePresets(j("""{"preset":{"preset":{"id":[],"name":[],"position_pan":[],"position_tilt":[],"read_only":[]}}}""")))
        assertEquals(setOf("ptz", "lensMask"), Camera.parseComponents(j("""{"app_component":{"app_component_list":[{"name":"ptz","version":1},{"name":"lensMask","version":2}]}}""")))
    }

    @Test fun multipleRequestReplies() {
        val ok = CameraClient.unwrap(j("""{"error_code":0,"result":{"responses":[{"method":"getLensMaskConfig","result":{"lens_mask":{}},"error_code":0}]}}"""))
        assertEquals("""{"lens_mask":{}}""", ok.toString())
        try {
            CameraClient.unwrap(j("""{"error_code":0,"result":{"responses":[{"method":"motorMove","result":{},"error_code":-64304}]}}"""))
            fail()
        } catch (e: TapoException.DeviceError) {
            assertEquals(-64304, e.code)
        }
        for (bad in listOf("{}", """{"result":{}}""", """{"result":{"responses":[]}}""")) {
            try { CameraClient.unwrap(j(bad)); fail(bad) } catch (_: TapoException.Unreachable) {}
        }
        try { CameraClient.unwrap(j("""{"error_code":-40210}""")); fail() } catch (e: TapoException.DeviceError) { assertEquals(-40210, e.code) }
    }

    @Test fun lockoutsAreRecognised() {
        assertEquals(1800, CameraClient.lockout(j("""{"error_code":-40404,"data":{"code":-40404,"sec_left":1800}}""")))
        assertEquals(60, CameraClient.lockout(j("""{"error_code":-40401,"result":{"data":{"sec_left":60}}}""")))
        assertNull(CameraClient.lockout(j("""{"error_code":-40413,"result":{"data":{"nonce":"x"}}}""")))
        try { CameraClient.lockout(j("""{"data":{"code":-40404}}""")); fail() } catch (_: TapoException.Locked) {}
    }

    /** Without a confirmed certificate or a saved account, a camera is never contacted. */
    @Test fun nothingIsSentWithoutCertificateOrAccount() = runBlocking {
        val ip = InetAddress.getByName("192.168.1.250") as java.net.Inet4Address
        val noPin = CameraClient(ip, { null }) { AccountParts(true, null, CameraSecret.of("x")) }
        try { noPin.call(TapoMethods.CAM_GET_DEVICE_INFO, Camera.deviceInfoParams()); fail() } catch (_: TapoException.CertificateChanged) {}
        val noAccount = CameraClient(ip, { "AA:BB" }) { AccountParts(false, null, null) }
        try { noAccount.call(TapoMethods.CAM_GET_DEVICE_INFO, Camera.deviceInfoParams()); fail() } catch (_: TapoException.NoAccount) {}
        val old = CameraClient(ip, { "AA:BB" }) { AccountParts(true, null, null) }
        try { old.call(TapoMethods.CAM_GET_DEVICE_INFO, Camera.deviceInfoParams()); fail() } catch (_: TapoException.AccountIncomplete) {}
        val legacy = PassthroughClient(ip, { AccountParts(false, null, null) }) { null }
        try { legacy.call(TapoMethods.GET_DEVICE_INFO, null); fail() } catch (_: TapoException.NoAccount) {}
        val legacyOld = PassthroughClient(ip, { AccountParts(true, null, null) }) { null }
        try { legacyOld.call(TapoMethods.GET_DEVICE_INFO, null); fail() } catch (_: TapoException.AccountIncomplete) {}
    }

    @Test fun passthroughCookies() {
        assertEquals("TP_SESSIONID=abc" to 1440L, PassthroughClient.parseCookie(listOf("TP_SESSIONID=abc;TIMEOUT=1440")))
        assertEquals("SESSIONID=xyz" to null, PassthroughClient.parseCookie(listOf("SESSIONID=xyz; Path=/")))
    }

    @Test fun discoveryReadsLoginVersionAndCameras() {
        val plug = """{"result":{"device_type":"SMART.TAPOPLUG","device_model":"P100","mac":"AA-BB-CC-DD-EE-FF","mgt_encrypt_schm":{"encrypt_type":"AES","is_support_https":false,"lv":2}},"error_code":0}"""
        val f = Discovery.parseDiscoveryReply(ByteArray(16) + plug.toByteArray(), "192.168.1.31")!!
        assertEquals(2, f.loginVersion)
        assertTrue(f.legacy)
        val cam = """{"result":{"device_type":"SMART.IPCAMERA","device_model":"C200","mgt_encrypt_schm":{"is_support_https":true}},"error_code":0}"""
        val c = Discovery.parseDiscoveryReply(ByteArray(16) + cam.toByteArray(), "192.168.1.32")!!
        assertTrue(c.camera)
        assertTrue(!c.legacy)
        assertNull(c.loginVersion)
    }
}
