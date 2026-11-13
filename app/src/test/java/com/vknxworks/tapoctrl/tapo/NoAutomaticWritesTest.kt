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
