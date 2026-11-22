package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** A device that has spoken KLAP is never switched to securePassthrough; camera crypt rounds are capped. */
class KlapPinningTest {
    @Test fun fallBackOnlyForDevicesNeverSeenSpeakingKlap() {
        assertTrue(SavedProtocol.mayFallBack(SavedProtocol.UNKNOWN, klapSeenHere = false))
        assertTrue(SavedProtocol.mayFallBack(null, klapSeenHere = false))
        assertTrue(SavedProtocol.mayFallBack(SavedProtocol.PASSTHROUGH, klapSeenHere = false))
        assertFalse(SavedProtocol.mayFallBack(SavedProtocol.KLAP, klapSeenHere = false))
        assertFalse(SavedProtocol.mayFallBack(SavedProtocol.UNKNOWN, klapSeenHere = true))
        assertFalse(SavedProtocol.mayFallBack(SavedProtocol.PASSTHROUGH, klapSeenHere = true))
    }

    @Test fun klapIsNeverReplacedBySecurePassthroughWhenSaving() {
        assertTrue(SavedProtocol.mayRecord(SavedProtocol.UNKNOWN, SavedProtocol.KLAP))
        assertTrue(SavedProtocol.mayRecord(SavedProtocol.UNKNOWN, SavedProtocol.PASSTHROUGH))
        // Older firmware updated to KLAP: learnt.
        assertTrue(SavedProtocol.mayRecord(SavedProtocol.PASSTHROUGH, SavedProtocol.KLAP))
        assertFalse(SavedProtocol.mayRecord(SavedProtocol.KLAP, SavedProtocol.PASSTHROUGH))
        assertFalse(SavedProtocol.mayRecord(SavedProtocol.KLAP, SavedProtocol.KLAP))
    }

    /** The only switch to securePassthrough in SmartClient goes through the pinning check. */
    @Test fun smartClientChecksThePinBeforeFallingBack() {
        val text = File("src/main/java/com/vknxworks/tapoctrl/tapo/PassthroughClient.kt").readText()
        val call = text.substringAfter("class SmartClient(").substringAfter("override suspend fun call(")
        val check = call.indexOf("SavedProtocol.mayFallBack(")
        val switch = call.indexOf("usesLegacy = true")
        assertTrue(check in 0 until switch)
        assertTrue(call.contains("throw TapoException.ProtocolChanged()"))
        assertEquals(1, Regex("usesLegacy = true").findAll(text).count())
    }

    private fun shadow(rounds: Int) = Json.parseToJsonElement(
        """{"type":"password_shadow","params":{"passwd_id":5,"passwd_salt":"x1hYMevsEYq2APg+","passwd_rounds":$rounds}}""",
    ).jsonObject

    @Test fun absurdCryptRoundsStopTheLogin() {
        for (rounds in listOf(CameraLogin.MAX_CRYPT_ROUNDS + 1, 999_999_999)) {
            try {
                CameraLogin.tpapCredential("pass", shadow(rounds))
                fail("rounds=$rounds was accepted")
            } catch (e: CameraLogin.UnsupportedTransform) {
                assertTrue(e.what, e.what.contains("rounds=$rounds"))
            }
        }
        val prefix = Json.parseToJsonElement(
            """{"type":"password_shadow","params":{"passwd_id":5,"passwd_prefix":"${'$'}5${'$'}rounds=5000000${'$'}abc${'$'}"}}""",
        ).jsonObject
        try {
            CameraLogin.tpapCredential("pass", prefix)
            fail("rounds in the prefix were accepted")
        } catch (_: CameraLogin.UnsupportedTransform) {
        }
    }

    @Test fun normalRoundsStillWork() {
        val r: JsonObject = shadow(5000)
        assertTrue(CameraLogin.tpapCredential("pass", r).startsWith("${'$'}5${'$'}"))
    }
}
