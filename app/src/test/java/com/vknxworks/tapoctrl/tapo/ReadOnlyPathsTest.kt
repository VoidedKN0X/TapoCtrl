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
        assertTrue(TapoMethods.READS.all { it.startsWith("get_") })
        assertFalse(TapoMethods.isRead(TapoMethods.SET_DEVICE_INFO))
        assertFalse(TapoMethods.isRead(TapoMethods.FW_DOWNLOAD))
    }

    @Test fun writesAreNeverRepeatedAfterABrokenConnection() {
        assertFalse(KlapClient.retriesAfterBrokenConnection(TapoMethods.SET_DEVICE_INFO))
        assertFalse(KlapClient.retriesAfterBrokenConnection(TapoMethods.FW_DOWNLOAD))
        assertTrue(KlapClient.retriesAfterBrokenConnection(TapoMethods.GET_DEVICE_INFO))
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
            }
            val writes = Regex("""TapoMethods\.(SET_DEVICE_INFO|FW_DOWNLOAD)""").findAll(text).count()
            when (f.name) {
                "Devices.kt" -> assertEquals("Devices.kt: one set_device_info and one fw_download call site", 2, writes)
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
        assertFalse(addFn.contains("TapoMethods.SET") || addFn.contains("TapoMethods.FW"))
    }
}
