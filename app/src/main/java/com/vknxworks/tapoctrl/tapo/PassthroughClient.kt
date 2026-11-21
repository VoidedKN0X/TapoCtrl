package com.vknxworks.tapoctrl.tapo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.Inet4Address
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** What the saved account offers the older protocols and cameras. */
class AccountParts(
    /** Whether any account is saved at all. Without one no device is contacted. */
    val saved: Boolean,
    /** Missing for an account saved before 0.6 (enter it again). */
    val legacy: LegacySecret?,
    val camera: CameraSecret?,
)

/**
 * A securePassthrough connection to one device with older firmware (see [PassthroughCrypto] for the protocol and
 * its sources). Requests are serialised. The login uses only your saved account: version 2 (hashes) when the
 * device announced it, otherwise version 1 (the password itself, if you chose to keep it) and then version 2, as
 * plugp100 does. A login the device refuses stops there.
 */
class PassthroughClient(
    val ip: Inet4Address,
    private val account: () -> AccountParts,
    /** The login version the device announced in discovery ("lv"), if it did. */
    private val loginVersion: () -> Int?,
) : TapoCaller {
    private class Session(val key: ByteArray, val iv: ByteArray, val cookie: String?, val expiresAt: Long) {
        var token: String? = null
    }

    private val mutex = Mutex()
    private var session: Session? = null
    private val terminalUuid: String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(UUID.randomUUID().toString().toByteArray()))

    fun reset() {
        session = null
    }

    override suspend fun call(method: String, params: JsonObject?): JsonElement = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                callOnce(method, params, retry = true)
            } catch (e: Retry) {
                reset()
                callOnce(method, params, retry = false)
            }
        }
    }

    private class Retry : Exception()

    private fun callOnce(method: String, params: JsonObject?, retry: Boolean): JsonElement {
        val s = session?.takeIf { it.token != null && System.currentTimeMillis() < it.expiresAt } ?: login()
        val request = buildJsonObject {
            put("method", method)
            if (params != null) put("params", params)
            put("request_time_milis", System.currentTimeMillis())
            put("terminal_uuid", terminalUuid)
        }
        val plain = try {
            exchange(s, request.toString())
        } catch (e: IOException) {
            // Only reads are sent again: a command that changes the device may have arrived already.
            if (retry && KlapClient.retriesAfterBrokenConnection(method)) throw Retry()
            throw TapoException.Unreachable(e)
        } catch (e: SessionGone) {
            // The device refused the session before acting on the request: safe to log in again, once.
            if (retry) throw Retry()
            throw TapoException.Unreachable(e)
        }
        val (code, result) = KlapClient.parseReply(plain.toByteArray(Charsets.UTF_8))
        if (code == SESSION_EXPIRED && retry) throw Retry()
        if (code != 0) throw TapoException.DeviceError(code)
        return result
    }

    private class SessionGone : Exception()

    /** One securePassthrough round trip: the decrypted reply text. */
    private fun exchange(s: Session, json: String): String {
        val body = buildJsonObject {
            put("method", "securePassthrough")
            putJsonObject("params") { put("request", AesCbc.encrypt(s.key, s.iv, json.toByteArray(Charsets.UTF_8))) }
        }.toString().toByteArray(Charsets.UTF_8)
        val path = s.token?.let { "/app?token=$it" } ?: "/app"
        val reply = LanHttp.post(ip, path, body, s.cookie, contentType = "application/json")
        if (reply.code != 200) throw TapoException.Unreachable(IOException("HTTP ${reply.code}"))
        val outer = try {
            Json.parseToJsonElement(reply.body.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw TapoException.Unreachable(e)
        }
        val outerCode = (outer["error_code"] as? JsonPrimitive)?.intOrNull ?: 0
        if (outerCode == SESSION_EXPIRED) throw SessionGone()
        if (outerCode != 0) throw TapoException.DeviceError(outerCode)
        val encrypted = ((outer["result"] as? JsonObject)?.get("response") as? JsonPrimitive)?.content
            ?: throw TapoException.Unreachable(IOException("No response"))
        return try {
            AesCbc.decrypt(s.key, s.iv, encrypted).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw TapoException.Unreachable(e)
        } catch (e: IllegalArgumentException) {
            throw TapoException.Unreachable(e)
        }
    }

    private fun handshake(): Session {
        val kp = PassthroughCrypto.newKeyPair()
        val reply = try {
            LanHttp.post(ip, "/app", PassthroughCrypto.handshakeBody(kp).toByteArray(Charsets.UTF_8), null, contentType = "application/json")
        } catch (e: IOException) {
            throw TapoException.Unreachable(e)
        }
        if (reply.code != 200) throw TapoException.Unreachable(IOException("HTTP ${reply.code}"))
        val json = try {
            Json.parseToJsonElement(reply.body.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw TapoException.Unreachable(e)
        }
        val code = (json["error_code"] as? JsonPrimitive)?.intOrNull ?: 0
        if (code != 0) throw TapoException.DeviceError(code)
        val keyB64 = ((json["result"] as? JsonObject)?.get("key") as? JsonPrimitive)?.content
            ?: throw TapoException.Unreachable(IOException("No key"))
        val (key, iv) = try {
            PassthroughCrypto.sessionKey(kp.private, keyB64)
        } catch (e: GeneralSecurityException) {
            throw TapoException.Unreachable(e)
        } catch (e: IllegalArgumentException) {
            throw TapoException.Unreachable(e)
        }
        val (cookie, timeout) = parseCookie(reply.cookies)
        // Renew 20 minutes before the device's TIMEOUT (seconds; 24 h when it doesn't say), as python-kasa does.
        val seconds = (timeout ?: 86_400L).coerceIn(1_500L, 86_400L)
        return Session(key, iv, cookie, System.currentTimeMillis() + (seconds - 1_200) * 1000)
    }

    /** Handshake and login_device with your own account only. */
    private fun login(): Session {
        session = null
        val acct = account()
        // No account: the device isn't contacted.
        if (!acct.saved) throw TapoException.NoAccount()
        val secret = acct.legacy ?: throw TapoException.AccountIncomplete()
        val lv = loginVersion()
        val versions = when {
            lv == 2 -> listOf(true)
            secret.password != null -> listOf(false, true)
            else -> listOf(true)
        }
        for (v2 in versions) {
            val params = PassthroughCrypto.loginParams(secret, v2) ?: continue
            // A fresh handshake for each login: after a refused login the old one is no longer accepted.
            val s = handshake()
            val request = buildJsonObject {
                put("method", "login_device")
                put("params", params)
                put("request_time_milis", System.currentTimeMillis())
            }
            val plain = try {
                exchange(s, request.toString())
            } catch (e: IOException) {
                throw TapoException.Unreachable(e)
            } catch (e: SessionGone) {
                throw TapoException.Unreachable(e)
            }
            val (code, result) = KlapClient.parseReply(plain.toByteArray(Charsets.UTF_8))
            if (code == LOGIN_ERROR) continue
            if (code != 0) throw TapoException.DeviceError(code)
            val token = ((result as? JsonObject)?.get("token") as? JsonPrimitive)?.content
                ?: throw TapoException.Unreachable(IOException("No token"))
            s.token = token
            session = s
            return s
        }
        // Refused. If the device may want version 1 and the password wasn't kept, say how to fix that.
        if (lv != 2 && secret.password == null) throw TapoException.NeedsPassword()
        throw TapoException.WrongAccount()
    }

    companion object {
        private const val SESSION_EXPIRED = 9999
        private const val LOGIN_ERROR = -1501

        /** "TP_SESSIONID=…;TIMEOUT=1440" (or SESSIONID on some firmware) → ("TP_SESSIONID=…", 1440). */
        fun parseCookie(headers: List<String>): Pair<String?, Long?> {
            var id: String? = null
            var timeout: Long? = null
            headers.flatMap { it.split(';') }.map { it.trim() }.forEach { part ->
                val k = part.substringBefore('=').trim()
                val v = part.substringAfter('=', "").trim()
                when {
                    (k.equals("TP_SESSIONID", true) || k.equals("SESSIONID", true)) && v.isNotEmpty() && id == null -> id = "$k=$v"
                    k.equals("TIMEOUT", true) -> timeout = v.toLongOrNull()
                }
            }
            return id to timeout
        }
    }
}

/**
 * The connection to one plug, bulb, strip, switch or hub: KLAP first; when the device has no KLAP (older
 * firmware: handshake1 answers 404 or not 48 bytes, before anything else is sent), securePassthrough from then on.
 */
class SmartClient(
    val ip: Inet4Address,
    klapAccount: () -> List<AuthHash>,
    account: () -> AccountParts,
    loginVersion: () -> Int?,
) : TapoCaller {
    private val klap = KlapClient(ip, klapAccount)
    private val legacy = PassthroughClient(ip, account, loginVersion)

    @Volatile
    var usesLegacy = false
        private set

    fun reset() {
        klap.reset()
        legacy.reset()
    }

    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        if (usesLegacy) return legacy.call(method, params)
        return try {
            klap.call(method, params)
        } catch (e: TapoException.LegacyFirmware) {
            // KLAP stopped at handshake1, before the request was sent: the request goes once, over the older protocol.
            usesLegacy = true
            legacy.call(method, params)
        }
    }
}
