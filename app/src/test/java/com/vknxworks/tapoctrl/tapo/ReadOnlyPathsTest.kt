package com.vknxworks.tapoctrl.tapo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * TapoCtrl must never change a device on its own. These tests send every automatic path (refresh, add, energy,
 * firmware check and progress) through a fake device that fails the test if anything but a read arrives, and check
 * the sources so that writes are only sent from the explicit user actions.
 */
class ReadOnlyPathsTest {
    /** A fake device: answers reads, fails the test on anything that could change it. */
    private class FakeDevice : TapoCaller {
        val sent = mutableListOf<String>()
        override suspend fun call(method: String, params: JsonObject?): JsonElement {
            sent += method
            if (method.startsWith("set_") || method.startsWith("fw_") || !TapoMethods.isRead(method)) {
                fail("A read-only path sent $method")
            }
            return when (method) {
                TapoMethods.GET_DEVICE_INFO -> buildJsonObject { put("model", "P110"); put("type", "SMART.TAPOPLUG"); put("device_on", false) }
                TapoMethods.GET_ENERGY_USAGE -> buildJsonObject { put("today_energy", 10); put("month_energy", 100); put("current_power", 1000) }
                TapoMethods.GET_CURRENT_POWER -> buildJsonObject { put("current_power", 1) }
                TapoMethods.GET_LATEST_FW -> buildJsonObject { put("need_to_upgrade", true); put("fw_ver", "1.2.3") }
                else -> buildJsonObject { put("status", 0); put("download_progress", 0) }
            }
        }
    }

    @Test fun automaticPathsOnlyRead() = runBlocking {
        val d = FakeDevice()
        repeat(3) { DeviceReads.info(d) } // app start, resume, pull to refresh
        DeviceReads.energy(d)
        DeviceReads.latestFirmware(d)
        DeviceReads.downloadState(d)
        assertTrue(d.sent.isNotEmpty())
        assertTrue("Only reads: ${d.sent}", d.sent.all { TapoMethods.isRead(it) })
        assertTrue(d.sent.none { it.startsWith("set_") || it.startsWith("fw_") })
    }

    @Test fun readOnlyRefusesWritesBeforeTheyReachTheDevice() = runBlocking {
        val d = FakeDevice()
        for (m in listOf(TapoMethods.SET_DEVICE_INFO, TapoMethods.FW_DOWNLOAD, "set_lighting_effect", "device_reboot", "device_reset")) {
            try {
                d.readOnly().call(m, buildJsonObject { put("device_on", true) })
                fail("$m got through")
            } catch (e: WriteRefused) {
                // expected
            }
        }
        assertEquals(emptyList<String>(), d.sent)
    }

    @Test fun readsListHasNoWrites() {
        assertTrue(TapoMethods.READS.all { it.startsWith("get_") || it == TapoMethods.COMPONENT_NEGO || Regex("^get[A-Z]").containsMatchIn(it) })
        for (w in listOf(TapoMethods.CAM_SET_LENS_MASK, TapoMethods.CAM_MOTOR_MOVE, TapoMethods.CAM_MOTOR_TO_PRESET, TapoMethods.CAM_REBOOT)) {
            assertFalse(w, TapoMethods.isRead(w))
        }
        assertFalse(TapoMethods.isRead(TapoMethods.SET_DEVICE_INFO))
        assertFalse(TapoMethods.isRead(TapoMethods.FW_DOWNLOAD))
        assertFalse(TapoMethods.isRead(TapoMethods.SET_LED_INFO))
        assertFalse(TapoMethods.isRead(TapoMethods.SET_AUTO_OFF_CONFIG))
        assertFalse(TapoMethods.isRead(TapoMethods.CONTROL_CHILD))
    }

    @Test fun writesAreNeverRepeatedAfterABrokenConnection() {
        assertFalse(KlapClient.retriesAfterBrokenConnection(TapoMethods.SET_DEVICE_INFO))
        assertFalse(KlapClient.retriesAfterBrokenConnection(TapoMethods.FW_DOWNLOAD))
        assertTrue(KlapClient.retriesAfterBrokenConnection(TapoMethods.GET_DEVICE_INFO))
        // Cameras and older firmware use the same rule.
        for (w in listOf(TapoMethods.CAM_SET_LENS_MASK, TapoMethods.CAM_MOTOR_MOVE, TapoMethods.CAM_MOTOR_TO_PRESET, TapoMethods.CAM_REBOOT)) {
            assertFalse(w, KlapClient.retriesAfterBrokenConnection(w))
        }
        assertTrue(KlapClient.retriesAfterBrokenConnection(TapoMethods.CAM_GET_LENS_MASK))
        val sources = File("src/main/java/com/vknxworks/tapoctrl/tapo")
        for (name in listOf("CameraClient.kt", "PassthroughClient.kt")) {
            val text = File(sources, name).readText()
            assertTrue("$name: a broken connection retries only reads", text.contains("if (retry && KlapClient.retriesAfterBrokenConnection(method)) throw Retry()"))
            // Login steps are never repeated on their own after a broken connection either.
            assertFalse(name, text.substringAfter("private fun login()").contains("throw Retry()"))
        }
    }

    /** A fake camera: answers reads in the camera's format, fails the test on anything that could change it. */
    @Test fun cameraReadsOnlyRead() = runBlocking {
        val sent = mutableListOf<String>()
        val camera = TapoCaller { method, _ ->
            sent += method
            if (!TapoMethods.isRead(method)) fail("A read-only path sent $method to a camera")
            kotlinx.serialization.json.Json.parseToJsonElement(
                when (method) {
                    TapoMethods.CAM_GET_DEVICE_INFO -> """{"device_info":{"basic_info":{"device_model":"C200","device_alias":"Hall","device_type":"SMART.IPCAMERA","sw_version":"1.3.17 Build 260112","hw_version":"1.0","mac":"28-EE-52-00-00-00"}}}"""
                    TapoMethods.CAM_GET_COMPONENTS -> """{"app_component":{"app_component_list":[{"name":"ptz","version":1},{"name":"lensMask","version":1}]}}"""
                    TapoMethods.CAM_GET_LENS_MASK -> """{"lens_mask":{"lens_mask_info":{"enabled":"on"}}}"""
                    TapoMethods.CAM_GET_PRESETS -> """{"preset":{"preset":{"id":["1","2"],"name":["Door",""]}}}"""
                    else -> "{}"
                },
            )
        }
        repeat(3) {
            val status = DeviceReads.camera(camera, null)
            assertEquals("C200", status.model)
            assertEquals("Hall", status.name)
            assertEquals(true, status.privacy)
            assertTrue(status.canPanTilt)
            assertEquals(listOf(CameraPreset("1", "Door"), CameraPreset("2", "2")), status.presets)
        }
        assertTrue(sent.all { TapoMethods.isRead(it) })
        for (m in listOf(TapoMethods.CAM_SET_LENS_MASK, TapoMethods.CAM_MOTOR_MOVE, TapoMethods.CAM_MOTOR_TO_PRESET, TapoMethods.CAM_REBOOT)) {
            try {
                camera.readOnly().call(m, null)
                fail("$m got through")
            } catch (_: WriteRefused) {
            }
        }
    }

    /** Writes appear only in DeviceRepository's user actions; discovery sends no methods at all. */
    @Test fun writesOnlyInUserActions() {
        val src = File("src/main/java/com/vknxworks/tapoctrl")
        assertTrue("sources not found from ${File(".").absolutePath}", src.isDirectory)
        val files = src.walkTopDown().filter { it.extension == "kt" }.toList()
        for (f in files) {
            val text = f.readText()
            if (f.name != "TapoMethods.kt") {
                assertFalse("${f.name} names a write method directly", text.contains("\"set_device_info\"") || text.contains("\"fw_download\""))
                for (w in listOf("\"setLensMaskConfig\"", "\"motorMove", "\"rebootDevice\"")) assertFalse("${f.name} names $w directly", text.contains(w))
            }
            val writes = Regex("""TapoMethods\.(SET_DEVICE_INFO|FW_DOWNLOAD|SET_LED_INFO|SET_AUTO_OFF_CONFIG|CONTROL_CHILD|SET_LIGHTING_EFFECT|SET_DYNAMIC_LIGHT_EFFECT_RULE_ENABLE|CAM_SET_LENS_MASK|CAM_MOTOR_MOVE|CAM_MOTOR_TO_PRESET|CAM_REBOOT)\b""").findAll(text).count()
            when (f.name) {
                // set_device_info (the device and, wrapped in control_child, one socket), fw_download,
                // set_led_info, set_auto_off_config and the two effect calls: each from one user action.
                // Cameras: privacy mode, a pan/tilt step, a saved position and restart, each from one user action.
                "Devices.kt" -> assertEquals("Devices.kt: the write call sites", 12, writes)
                else -> assertEquals("${f.name} sends a write", 0, writes)
            }
        }
        val discovery = files.first { it.name == "Discovery.kt" }.readText()
        assertFalse(discovery.contains(".call("))
        // The two write call sites are the user actions: set() (power, light, rename) and startFirmwareDownload().
        val devices = files.first { it.name == "Devices.kt" }.readText()
        val setFn = devices.substringAfter("suspend fun set(").substringBefore("suspend fun setOn(")
        assertTrue(setFn.contains("TapoMethods.SET_DEVICE_INFO"))
        val fwFn = devices.substringAfter("suspend fun startFirmwareDownload(").substringBefore("suspend fun downloadState(")
        assertTrue(fwFn.contains("TapoMethods.FW_DOWNLOAD, null"))
        // Refresh and add use only the read paths.
        val refreshFn = devices.substringAfter("suspend fun refresh(").substringBefore("suspend fun set(")
        assertFalse(refreshFn.contains("TapoMethods.SET") || refreshFn.contains("TapoMethods.FW"))
        val addFn = devices.substringAfter("suspend fun add(").substringBefore("suspend fun remove(")
        assertFalse(addFn.contains("TapoMethods.SET") || addFn.contains("TapoMethods.FW") || addFn.contains("TapoMethods.CAM_") || addFn.contains("cameraCommand("))
        // Adding and refreshing a camera only read; each camera write sits in its own user action.
        assertFalse(refreshFn.contains("TapoMethods.CAM_") || refreshFn.contains("cameraCommand("))
        fun fn(name: String) = devices.substringAfter("suspend fun $name(").substringBefore("\n    }\n")
        assertTrue(fn("setPrivacy").contains("TapoMethods.CAM_SET_LENS_MASK"))
        assertTrue(fn("moveCamera").contains("TapoMethods.CAM_MOTOR_MOVE,"))
        assertTrue(fn("gotoCameraPreset").contains("TapoMethods.CAM_MOTOR_TO_PRESET"))
        assertTrue(fn("rebootCamera").contains("TapoMethods.CAM_REBOOT"))
    }
}
