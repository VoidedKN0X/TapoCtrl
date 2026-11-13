package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * TapoCtrl never switches a device on or off by itself. Besides [ReadOnlyPathsTest]: the device-changing actions are
 * only reachable from taps (never from a screen opening, resuming or recomposing), and nothing in the app can run
 * without you (no services, receivers, widgets, tiles or scheduled work).
 */
class NoAutomaticWritesTest {
    private val src = File("src/main/java/com/vknxworks/tapoctrl")
    private val writeCalls = Regex("""\.(setOn|set|rename|startFirmwareDownload)\(""")

    private fun sources() = src.walkTopDown().filter { it.extension == "kt" }.toList().also {
        assertTrue("sources not found from ${File(".").absolutePath}", it.isNotEmpty())
    }

    /** The block that follows each [start] match: from its first "{" to the matching "}". */
    private fun blocks(text: String, start: Regex): List<String> = start.findAll(text).map { m ->
        val open = text.indexOf('{', m.range.first)
        var depth = 0
        var end = open
        while (end < text.length) {
            when (text[end]) {
                '{' -> depth++
                '}' -> if (--depth == 0) break
            }
            end++
        }
        text.substring(open, minOf(end + 1, text.length))
    }.toList()

    @Test fun effectsAndInitBlocksNeverChangeADevice() {
        val automatic = Regex("""\b(LaunchedEffect|LifecycleResumeEffect|LifecycleStartEffect|DisposableEffect|SideEffect)\s*[({][^{\n]*|(?m)^\s*init\s*(?=\{)""")
        for (f in sources()) {
            for (b in blocks(f.readText(), automatic)) {
                assertFalse("${f.name}: an automatic block changes a device:\n$b", writeCalls.containsMatchIn(b) || b.contains("TapoMethods.SET") || b.contains("TapoMethods.FW"))
            }
        }
    }

    @Test fun deviceChangesOnlyFromTheDeviceScreens() {
        val allowed = setOf("Devices.kt", "DeviceScreen.kt", "HomeScreen.kt", "FirmwareScreen.kt")
        for (f in sources()) {
            val text = f.readText()
            val calls = Regex("""devices\.(setOn|set|rename|startFirmwareDownload)\(""").findAll(text).count()
            if (f.name !in allowed) assertEquals("${f.name} changes a device", 0, calls)
        }
        // Each screen call sits in a click, commit or confirm handler.
        for (name in listOf("DeviceScreen.kt", "HomeScreen.kt", "FirmwareScreen.kt")) {
            val lines = sources().first { it.name == name }.readLines()
            lines.forEachIndexed { i, line ->
                if (Regex("""devices\.(setOn|set|rename|startFirmwareDownload)\(""").containsMatchIn(line)) {
                    val context = lines.subList(maxOf(0, i - 12), i + 1).joinToString("\n")
                    assertTrue("$name:${i + 1} isn't inside a tap handler:\n$context",
                        Regex("""onClick|onCommit|onConfirm|onCheckedChange|fun send\(|fun toggle\(|fun install\(|Dialog\(""").containsMatchIn(context))
                }
            }
        }
    }

    @Test fun nothingRunsInTheBackground() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (tag in listOf("<service", "<receiver", "android.appwidget", "BIND_QUICK_SETTINGS_TILE", "RECEIVE_BOOT_COMPLETED", "SCHEDULE_EXACT_ALARM")) {
            assertFalse("The manifest has $tag", manifest.contains(tag))
        }
        for (f in sources()) {
            val text = f.readText()
            for (api in listOf("WorkManager", "AlarmManager", "JobScheduler", "TileService", "AppWidgetProvider", "BroadcastReceiver")) {
                assertFalse("${f.name} uses $api", text.contains(api))
            }
        }
    }

    /** The energy graphs only read: every range, new and old firmware, through a plug that fails on anything else. */
    @Test fun energyGraphsOnlyRead() = kotlinx.coroutines.runBlocking {
        for (hasEnergyData in listOf(true, false)) {
            val sent = mutableListOf<String>()
            val plug = TapoCaller { method, _ ->
                sent += method
                if (!TapoMethods.isRead(method) || method.startsWith("set_") || method.startsWith("fw_")) fail("The energy graphs sent $method")
                when (method) {
                    TapoMethods.GET_ENERGY_DATA -> if (hasEnergyData) kotlinx.serialization.json.Json.parseToJsonElement("""{"data":[1,2,3]}""") else throw TapoException.DeviceError(-1)
                    else -> kotlinx.serialization.json.Json.parseToJsonElement("""{"past24h":[1],"past7d":[[1]],"past30d":[1],"past1y":[1]}""")
                }
            }
            for (range in EnergyRange.entries) DeviceReads.energyHistory(plug, range, java.time.LocalDateTime.of(2026, 11, 13, 12, 0))
            assertTrue(sent.isNotEmpty())
            assertTrue("Only reads: $sent", sent.all { it == TapoMethods.GET_ENERGY_DATA || it == TapoMethods.GET_ENERGY_USAGE })
        }
        // The energy read is on the read-only list, and the read-only view still refuses writes.
        assertTrue(TapoMethods.isRead(TapoMethods.GET_ENERGY_DATA))
        try {
            TapoCaller { _, _ -> fail("reached the device"); error("") }.readOnly().call(TapoMethods.SET_DEVICE_INFO, null)
            fail("a write got through")
        } catch (_: WriteRefused) {
        }
    }

    /** The energy screen and its code never change a device and only use the read path. */
    @Test fun energyScreenNeverChangesADevice() {
        val files = sources().associateBy { it.name }
        for (name in listOf("EnergyScreen.kt", "EnergyHistory.kt")) {
            val text = files.getValue(name).readText()
            assertFalse("$name changes a device", writeCalls.containsMatchIn(text) || text.contains("TapoMethods.SET") || text.contains("TapoMethods.FW"))
            assertFalse("$name names a write", text.contains("set_") || text.contains("fw_download"))
        }
        assertTrue(files.getValue("EnergyScreen.kt").readText().contains("c.devices.energyHistory("))
        val history = files.getValue("EnergyHistory.kt").readText()
        assertTrue(history.substringAfter("suspend fun read(").contains(".readOnly()"))
        val devices = files.getValue("Devices.kt").readText()
        val fn = devices.substringAfter("suspend fun energyHistory(").substringBefore("suspend fun latestFirmware(")
        assertTrue(fn.contains("DeviceReads.energyHistory("))
        assertFalse(fn.contains("c.call("))
    }

    @Test fun malformedRepliesAreErrorsNotCrashes() {
        for (bad in listOf("", "[]", "{\"error_code\":\"x\"}", "{\"error_code\":{}}", "not json")) {
            try {
                KlapClient.parseReply(bad.toByteArray())
                fail("accepted $bad")
            } catch (_: TapoException.Unreachable) {
            }
        }
        val (code, result) = KlapClient.parseReply("""{"error_code":0,"result":{"device_on":false}}""".toByteArray())
        assertEquals(0, code)
        assertEquals(false, result.jsonObject["device_on"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(-1012 to JsonNull, KlapClient.parseReply("""{"error_code":-1012}""".toByteArray()))
        assertEquals(0, KlapClient.parseReply("{}".toByteArray()).first)
        // Sanity check of the helper used above.
        assertEquals(5, kotlinx.serialization.json.JsonPrimitive(5).int)
    }
}
