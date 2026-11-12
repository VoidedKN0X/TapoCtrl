package com.vknxworks.tapoctrl.tapo

import com.vknxworks.tapoctrl.net.LanAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Proxy
import java.net.URL
import java.security.GeneralSecurityException
import java.security.SecureRandom

/** Why talking to a device failed, in words the UI can show. */
sealed class TapoException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Not a private address: TapoCtrl never connects to it. */
    class NotLocal : TapoException("Not a local address")
    /** No reply, or the connection broke. */
    class Unreachable(cause: Throwable?) : TapoException("Unreachable", cause)
    /** The device answered but doesn't accept your saved Tapo account. TapoCtrl stops there; it tries nothing else. */
    class WrongAccount : TapoException("Wrong account")
    /** No account saved yet: TapoCtrl doesn't contact the device at all. */
    class NoAccount : TapoException("No account")
    /** Older firmware with the securePassthrough protocol, which TapoCtrl doesn't speak yet. */
    class LegacyFirmware : TapoException("Legacy firmware")
    /** The device answered a request with a non-zero error_code. */
    class DeviceError(val code: Int) : TapoException("Device error $code")
}

/** One HTTP exchange with a device on port 80. Proxies and redirects are never used. */
internal object LanHttp {
    class Reply(val code: Int, val body: ByteArray, val cookies: List<String>)

    fun post(ip: Inet4Address, path: String, body: ByteArray, cookie: String?, connectMs: Int = 3000, readMs: Int = 6000): Reply {
        if (!LanAddress.isPrivate(ip)) throw TapoException.NotLocal()
        val url = URL("http", LanAddress.text(ip), 80, path)
        val c = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("Accept", "*/*")
            if (cookie != null) c.setRequestProperty("Cookie", cookie)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { s -> s.readNBytes(MAX_BODY) } ?: ByteArray(0)
            val cookies = c.headerFields.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }
            return Reply(code, bytes, cookies)
        } finally {
            c.disconnect()
        }
    }

    private const val MAX_BODY = 1 shl 20
}

/**
 * A KLAP connection to one device. Requests are serialised; the session is set up on first use and again after
 * it expires, after a 401/403, or after the device stops understanding it (for example after a reboot).
 */
class KlapClient(
    val ip: Inet4Address,
    /** The saved account's hashes (v2 then v1); empty when no account is saved. */
    private val accountHashes: () -> List<AuthHash>,
) : TapoCaller {
    private val mutex = Mutex()
    private var session: KlapSession? = null
    private var cookie: String? = null
    private var expiresAt = 0L


    fun reset() {
        session = null
        cookie = null
        expiresAt = 0
    }

    /** Sends one method and returns its "result" (JsonNull when there's none). */
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
        val s = session?.takeIf { System.currentTimeMillis() < expiresAt } ?: handshake()
        val request = buildJsonObject {
            put("method", method)
            if (params != null) put("params", params)
            put("requestTimeMils", System.currentTimeMillis())
        }
        val (seq, body) = s.encrypt(request.toString().toByteArray(Charsets.UTF_8))
        val reply = try {
            LanHttp.post(ip, "/app/request?seq=$seq", body, cookie)
        } catch (e: IOException) {
            // A broken session after a reboot often shows up as a reset connection; try once more from scratch.
            // Only for reads: a command that changes the device may have arrived, so it's never sent twice.
            if (retry && retriesAfterBrokenConnection(method)) throw Retry()
            throw TapoException.Unreachable(e)
        }
        if (reply.code == 401 || reply.code == 403) { if (retry) throw Retry() else throw TapoException.WrongAccount() }
        if (reply.code != 200) throw TapoException.Unreachable(IOException("HTTP ${reply.code}"))
        val plain = try {
            s.decrypt(seq, reply.body)
        } catch (e: GeneralSecurityException) {
            if (retry && retriesAfterBrokenConnection(method)) throw Retry() else throw TapoException.Unreachable(e)
        } catch (e: IllegalArgumentException) {
            if (retry && retriesAfterBrokenConnection(method)) throw Retry() else throw TapoException.Unreachable(e)
        }
        val json = try {
            Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw TapoException.Unreachable(e)
        }
        val code = json["error_code"]?.jsonPrimitive?.int ?: 0
        if (code == SESSION_EXPIRED && retry) throw Retry()
        if (code != 0) throw TapoException.DeviceError(code)
        return json["result"] ?: JsonNull
    }


    private fun handshake(): KlapSession {
        reset()
        // Only your own account: without it there's nothing to try, so the device isn't contacted.
        val account = accountHashes()
        if (account.isEmpty()) throw TapoException.NoAccount()
        val local = ByteArray(16).also { random.nextBytes(it) }
        val h1 = try {
            LanHttp.post(ip, "/app/handshake1", local, null)
        } catch (e: IOException) {
            throw TapoException.Unreachable(e)
        }
        if (h1.code == 404 || h1.code == 405 || h1.code == 501) throw TapoException.LegacyFirmware()
        if (h1.code != 200) throw TapoException.Unreachable(IOException("HTTP ${h1.code}"))
        if (h1.body.size != 48) throw TapoException.LegacyFirmware()
        val remote = h1.body.copyOfRange(0, 16)
        val server = h1.body.copyOfRange(16, 48)
        val sessionCookie = parseCookie(h1.cookies)
        val auth = chooseAuth(account, local, remote, server)
        cookie = sessionCookie.first
        val h2 = try {
            LanHttp.post(ip, "/app/handshake2", KlapCrypto.clientHash(auth, local, remote), cookie)
        } catch (e: IOException) {
            throw TapoException.Unreachable(e)
        }
        if (h2.code != 200) throw TapoException.WrongAccount()
        val s = KlapSession(local, remote, auth.hash)
        session = s
        // Renew a little before the device's own TIMEOUT (seconds; 24 h when it doesn't say).
        val timeout = (sessionCookie.second ?: 86_400L).coerceIn(60L, 86_400L)
        expiresAt = System.currentTimeMillis() + (timeout - 30) * 1000
        return s
    }

    companion object {
        private val random = SecureRandom()
        private const val SESSION_EXPIRED = 9999

        /**
         * Whether a call may be repeated after a broken connection or an unreadable reply. Only reads: a write the
         * device may already have carried out is never sent a second time. (401/403 and error 9999 mean the device
         * rejected the session before acting, so those are retried for every method.)
         */
        fun retriesAfterBrokenConnection(method: String): Boolean = TapoMethods.isRead(method)

        /**
         * Picks the login for a handshake1 reply. Only the hashes of your own saved account are ever checked; there
         * are no built-in, blank or setup accounts. If none of them matches, it's [TapoException.WrongAccount]
         * and the handshake stops (no handshake2, no other attempts).
         */
        fun chooseAuth(account: List<AuthHash>, local: ByteArray, remote: ByteArray, server: ByteArray): AuthHash {
            if (account.isEmpty()) throw TapoException.NoAccount()
            return KlapCrypto.match(account, local, remote, server) ?: throw TapoException.WrongAccount()
        }

        /** "TP_SESSIONID=…;TIMEOUT=86400" → ("TP_SESSIONID=…", 86400). */
        fun parseCookie(headers: List<String>): Pair<String?, Long?> {
            var id: String? = null
            var timeout: Long? = null
            headers.flatMap { it.split(';') }.map { it.trim() }.forEach { part ->
                val k = part.substringBefore('=').trim()
                val v = part.substringAfter('=', "").trim()
                when {
                    k.equals("TP_SESSIONID", ignoreCase = true) && v.isNotEmpty() -> id = "TP_SESSIONID=$v"
                    k.equals("TIMEOUT", ignoreCase = true) -> timeout = v.toLongOrNull()
                }
            }
            return id to timeout
        }
    }
}
