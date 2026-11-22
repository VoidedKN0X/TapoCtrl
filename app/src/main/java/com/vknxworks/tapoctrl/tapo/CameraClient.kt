package com.vknxworks.tapoctrl.tapo

import com.vknxworks.tapoctrl.net.LanAddress
import com.vknxworks.tapoctrl.net.PinnedTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.math.BigInteger
import java.net.Inet4Address
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64

/**
 * A connection to one Tapo camera over HTTPS (port 443), trusting only the certificate you confirmed. It picks
 * the camera's login by itself, as pytapo does:
 * - asks `login` with sub_method "discover" (no password); a camera that offers TPAP (`tpap.pake` has 2) gets
 *   the SPAKE2+ login ([Spake2p]);
 * - otherwise the encrypt_type 3 login (python-kasa SslAesTransport): the first step sends no password, and the
 *   camera proves it knows yours (device_confirm) before TapoCtrl sends anything derived from it. A camera that
 *   answers that step with -40211 uses TPAP instead; one that offers only another encrypt_type gets the older
 *   hashed login (MD5 of the password).
 * The user is always "admin" with your Tapo account's password, the camera's own login for that account; there
 * are no built-in or default passwords. Every request is one method inside a `multipleRequest`.
 */
class CameraClient(
    val ip: Inet4Address,
    /** The certificate fingerprint you confirmed; null or empty means nothing is sent. */
    private val pin: () -> String?,
    private val account: () -> AccountParts,
) : TapoCaller {
    private enum class Kind { TPAP, SECURE, HASHED }

    private sealed interface Session {
        class Secure(val aes: CameraAesSession, val stok: String) : Session
        class Hashed(val stok: String) : Session
        class Tpap(val tpap: TpapSession, val stok: String, val expiresAt: Long) : Session
    }

    private val mutex = Mutex()
    private var session: Session? = null
    private var kind: Kind? = null
    private var tpapUserHashType: Int? = null
    /** Which TPAP passcode worked, so a re-login never spends a failed attempt on the other one. */
    private var tpapPasscode: Int? = null

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
            } catch (e: IOException) {
                throw if (PinnedTls.isPinMismatch(e)) TapoException.CertificateChanged() else TapoException.Unreachable(e)
            }
        }
    }

    private class Retry : Exception()

    /** The camera refused the session before acting on the request (expired, or another app logged in). */
    private class SessionGone : Exception()

    private fun pinOrThrow(): String = pin()?.takeIf { it.isNotBlank() } ?: throw TapoException.CertificateChanged()

    private fun callOnce(method: String, params: JsonObject?, retry: Boolean): JsonElement {
        val s = session?.takeIf { it !is Session.Tpap || System.currentTimeMillis() < it.expiresAt } ?: login()
        val request = buildJsonObject {
            put("method", "multipleRequest")
            putJsonObject("params") {
                putJsonArray("requests") {
                    add(buildJsonObject { put("method", method); if (params != null) put("params", params) })
                }
            }
        }.toString()
        val reply = try {
            exchange(s, request)
        } catch (e: IOException) {
            if (PinnedTls.isPinMismatch(e)) throw TapoException.CertificateChanged()
            // Only reads are sent again after a broken connection: a command may have arrived already.
            if (retry && KlapClient.retriesAfterBrokenConnection(method)) throw Retry()
            throw TapoException.Unreachable(e)
        } catch (e: SessionGone) {
            if (retry) throw Retry()
            throw TapoException.Unreachable(e)
        }
        return unwrap(reply)
    }

    /** One request over the session; the reply as JSON. */
    private fun exchange(s: Session, request: String): JsonObject = when (s) {
        is Session.Secure -> {
            val body = buildJsonObject {
                put("method", "securePassthrough")
                putJsonObject("params") { put("request", s.aes.encrypt(request)) }
            }.toString()
            val seq = s.aes.nextSeq()
            val r = post("/stok=${s.stok}/ds", body.toByteArray(Charsets.UTF_8), jsonHeaders() + mapOf("Seq" to seq.toString(), "Tapo_tag" to s.aes.tag(body, seq)))
            // 401: the session expired; 500: another app opened a session. Either way the request wasn't carried out.
            if (r.code == 401 || r.code == 500) throw SessionGone()
            if (r.code != 200) throw TapoException.Unreachable(IOException("HTTP ${r.code}"))
            val outer = parse(r.body)
            checkSession(outer)
            val enc = ((outer["result"] as? JsonObject)?.get("response") as? JsonPrimitive)?.content
            if (enc == null) {
                outer // Cameras answer some requests unencrypted.
            } else {
                try {
                    parse(s.aes.decrypt(enc).toByteArray(Charsets.UTF_8))
                } catch (e: GeneralSecurityException) {
                    throw TapoException.Unreachable(e)
                } catch (e: IllegalArgumentException) {
                    throw TapoException.Unreachable(e)
                }
            }
        }
        is Session.Hashed -> {
            val r = post("/stok=${s.stok}/ds", request.toByteArray(Charsets.UTF_8), jsonHeaders())
            if (r.code == 401) throw SessionGone()
            if (r.code != 200) throw TapoException.Unreachable(IOException("HTTP ${r.code}"))
            parse(r.body).also { checkSession(it) }
        }
        is Session.Tpap -> {
            val (seq, body) = s.tpap.encrypt(request.toByteArray(Charsets.UTF_8))
            val r = post("/stok=${s.stok}/ds", body, baseHeaders("application/octet-stream"))
            if (r.code != 200 && r.body.isEmpty()) throw TapoException.Unreachable(IOException("HTTP ${r.code}"))
            // Decrypt first, JSON second (a ciphertext starts with "{" whenever its sequence number does, as the
            // tapo crate notes). A reply that doesn't decrypt but is JSON is the camera refusing the request.
            val plain = try {
                s.tpap.decrypt(seq, r.body)
            } catch (e: GeneralSecurityException) {
                val code = runCatching { (parse(r.body)["error_code"] as? JsonPrimitive)?.intOrNull }.getOrNull()
                    ?: throw TapoException.Unreachable(e)
                if (code in TPAP_SESSION_ERRORS) throw SessionGone()
                throw TapoException.DeviceError(code)
            }
            parse(plain)
        }
    }

    private fun checkSession(outer: JsonObject) {
        val code = (outer["error_code"] as? JsonPrimitive)?.intOrNull ?: 0
        if (code == SESSION_EXPIRED) throw SessionGone()
    }

    // ---- login -------------------------------------------------------------------------------------------------

    private fun login(): Session {
        session = null
        pinOrThrow()
        val acct = account()
        // No account: the camera isn't contacted.
        if (!acct.saved) throw TapoException.NoAccount()
        val secret = acct.camera ?: throw TapoException.AccountIncomplete()
        val s = when (kind) {
            Kind.TPAP -> loginTpap(secret)
            Kind.HASHED -> loginHashed(secret)
            Kind.SECURE -> loginSecure(secret)
            null -> {
                val discover = postLogin(buildJsonObject { put("sub_method", "discover") })
                lockout(discover)?.let { throw TapoException.Locked(it) }
                val tpap = (discover["result"] as? JsonObject)?.get("tpap") as? JsonObject
                val pake = (tpap?.get("pake") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
                if (2 in pake) {
                    kind = Kind.TPAP
                    tpapUserHashType = (tpap?.get("user_hash_type") as? JsonPrimitive)?.intOrNull
                    loginTpap(secret)
                } else {
                    loginSecure(secret)
                }
            }
        }
        session = s
        return s
    }

    /** encrypt_type 3: step 1 without a password; step 2 only if the camera proved it knows your password. */
    private fun loginSecure(secret: CameraSecret): Session {
        val cnonce = randomHex(8)
        val r = postLogin(buildJsonObject { put("cnonce", cnonce); put("encrypt_type", "3"); put("username", CAMERA_USER) })
        val code = (r["error_code"] as? JsonPrimitive)?.intOrNull ?: 0
        if (code == TPAP_REQUIRED) {
            kind = Kind.TPAP
            return loginTpap(secret)
        }
        lockout(r)?.let { throw TapoException.Locked(it) }
        val data = (r["result"] as? JsonObject)?.get("data") as? JsonObject
        val nonce = (data?.get("nonce") as? JsonPrimitive)?.content
        val confirm = (data?.get("device_confirm") as? JsonPrimitive)?.content
        if (code == INVALID_NONCE && nonce != null && confirm != null) {
            kind = Kind.SECURE
            // Not a camera on your account: stop here, nothing derived from your password was sent.
            val pwdHash = CameraLogin.matchConfirm(secret, cnonce, nonce, confirm) ?: throw TapoException.WrongAccount()
            val r2 = postLogin(buildJsonObject {
                put("cnonce", cnonce)
                put("encrypt_type", "3")
                put("digest_passwd", CameraLogin.digestPasswd(cnonce, nonce, pwdHash))
                put("username", CAMERA_USER)
            })
            val code2 = (r2["error_code"] as? JsonPrimitive)?.intOrNull ?: 0
            lockout(r2)?.let { throw TapoException.Locked(it) }
            if (code2 == INVALID_NONCE) throw TapoException.WrongAccount()
            if (code2 != 0) throw TapoException.DeviceError(code2, "login encrypt_type 3, step 2")
            val result = r2["result"] as? JsonObject ?: throw TapoException.Unreachable(IOException("No result"))
            val stok = (result["stok"] as? JsonPrimitive)?.content ?: throw TapoException.Unreachable(IOException("No stok"))
            val seq = (result["start_seq"] as? JsonPrimitive)?.intOrNull ?: throw TapoException.Unreachable(IOException("No start_seq"))
            return Session.Secure(CameraAesSession(cnonce, nonce, pwdHash, seq), stok)
        }
        val types = (data?.get("encrypt_type") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        if (types != null && types != listOf("3")) {
            kind = Kind.HASHED
            return loginHashed(secret)
        }
        // Not a reply TapoCtrl knows: say what came back (codes and field names only).
        if (code == 0) throw TapoException.Unsupported("login encrypt_type 3, step 1: no nonce (fields: ${fieldNames(r)})")
        throw TapoException.DeviceError(code, "login encrypt_type 3, step 1")
    }

    /** The older login some camera firmware offers: the MD5 of your password, upper-case, over the pinned connection. */
    private fun loginHashed(secret: CameraSecret): Session {
        val r = postLogin(buildJsonObject { put("hashed", true); put("password", secret.md5Upper); put("username", CAMERA_USER) })
        lockout(r)?.let { throw TapoException.Locked(it) }
        val code = (r["error_code"] as? JsonPrimitive)?.intOrNull ?: 0
        val stok = ((r["result"] as? JsonObject)?.get("stok") as? JsonPrimitive)?.content
        if (code == 0 && stok != null) return Session.Hashed(stok)
        throw TapoException.WrongAccount()
    }

    /** TPAP: pake_register, then pake_share with the SPAKE2+ proof; the camera's proof is checked before use. */
    private fun loginTpap(secret: CameraSecret): Session {
        val passcodes = CameraLogin.tpapPasscodes(secret)
        val known = tpapPasscode
        // First login: the app's order. Re-login: only the passcode that worked, once more after 2 s (a C200 on
        // 1.4.6 refuses the first pake_share after a session ends, as pytapo notes).
        val attempts = if (known != null) listOf(known, known) else passcodes.indices.toList()
        for ((n, index) in attempts.withIndex()) {
            if (n > 0 && index == attempts[n - 1]) Thread.sleep(2_000)
            val s = tpapOnce(passcodes[index]) ?: continue
            tpapPasscode = index
            return s
        }
        throw TapoException.WrongAccount()
    }

    /** One SPAKE2+ exchange; null when the camera refused this passcode (-40401). */
    private fun tpapOnce(passcode: String): Session? {
        val userRandom = ByteArray(32).also { random.nextBytes(it) }
        val register = postLogin(buildJsonObject {
            put("sub_method", "pake_register")
            put("username", CameraLogin.tpapUser(tpapUserHashType))
            put("user_random", b64(userRandom))
            putJsonArray("cipher_suites") { add(1) }
            putJsonArray("encryption") { add("aes_128_ccm") }
            put("passcode_type", "userpw")
        })
        lockout(register)?.let { throw TapoException.Locked(it) }
        val reg = register["result"] as? JsonObject
            ?: throw TapoException.DeviceError((register["error_code"] as? JsonPrimitive)?.intOrNull ?: -1, "TPAP pake_register")
        // Everything checked before anything derived from your password is sent (pake_share).
        val (registration, credential) = try {
            val parsed = CameraLogin.tpapRegistration(reg)
            parsed to CameraLogin.tpapCredential(passcode, parsed.extraCrypt)
        } catch (e: CameraLogin.UnsupportedTransform) {
            throw TapoException.Unsupported("TPAP pake_register: ${e.what}")
        }
        val (w0, w1) = Spake2p.w0w1(credential, registration.salt, registration.iterations)
        val x = BigInteger(1, ByteArray(32).also { random.nextBytes(it) }).mod(P256.N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        val proof = Spake2p.prove(w0, w1, x, Spake2p.context(userRandom, registration.deviceRandom), ByteArray(0), ByteArray(0), registration.deviceShare)
            ?: throw TapoException.Unsupported("TPAP pake_register: dev_share isn't a usable P-256 point (${registration.deviceShare.size} bytes)")
        val share = postLogin(buildJsonObject {
            put("sub_method", "pake_share")
            put("user_share", b64(proof.share))
            put("user_confirm", b64(proof.confirm))
        })
        lockout(share)?.let { throw TapoException.Locked(it) }
        val result = share["result"] as? JsonObject
        if (result == null) {
            val code = (share["error_code"] as? JsonPrimitive)?.intOrNull ?: -1
            if (code == SESSION_EXPIRED) return null
            throw TapoException.DeviceError(code, "TPAP pake_share")
        }
        val devConfirm = (result["dev_confirm"] as? JsonPrimitive)?.content?.let(::unb64)
        if (devConfirm == null || !proof.verify(devConfirm)) throw TapoException.WrongAccount()
        val stok = (result["stok"] as? JsonPrimitive)?.content ?: throw TapoException.Unreachable(IOException("No stok"))
        val seq = (result["start_seq"] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw TapoException.Unreachable(IOException("No start_seq"))
        val expired = (result["expired"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 } ?: 3600L
        return Session.Tpap(TpapSession(proof.sharedKey, seq), stok, System.currentTimeMillis() + expired.coerceAtMost(86_400L) * 1000)
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------------

    private fun baseHeaders(contentType: String) = mapOf(
        "Content-Type" to contentType,
        "Accept" to contentType.substringBefore(';'),
        "requestByApp" to "true",
        "User-Agent" to "Tapo CameraClient Android",
        "Referer" to "https://${LanAddress.text(ip)}:443",
    )

    private fun jsonHeaders() = baseHeaders("application/json; charset=UTF-8")

    private fun post(path: String, body: ByteArray, headers: Map<String, String>): PinnedTls.Reply =
        PinnedTls.post(ip, pinOrThrow(), path, body, headers)

    /** One login step: {"method":"login","params":…} to "/", the reply as JSON (also for HTTP errors with a JSON body). */
    private fun postLogin(params: JsonObject): JsonObject {
        val body = buildJsonObject { put("method", "login"); put("params", params) }.toString().toByteArray(Charsets.UTF_8)
        val r = try {
            post("/", body, jsonHeaders())
        } catch (e: IOException) {
            throw if (PinnedTls.isPinMismatch(e)) TapoException.CertificateChanged() else TapoException.Unreachable(e)
        }
        return try {
            parse(r.body)
        } catch (e: TapoException) {
            throw TapoException.Unreachable(IOException("HTTP ${r.code}"))
        }
    }

    companion object {
        private val random = SecureRandom()
        private const val SESSION_EXPIRED = -40401
        private const val INVALID_NONCE = -40413
        private const val TPAP_REQUIRED = -40211
        private val TPAP_SESSION_ERRORS = setOf(-40401, -40421)

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String): ByteArray = try {
            Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw TapoException.Unreachable(e)
        }

        /** The field names of a reply and of its result (never values), for an error message. */
        internal fun fieldNames(r: JsonObject): String {
            val inner = (r["result"] as? JsonObject)?.keys?.sorted()?.joinToString(",")
            return (r.keys.sorted().joinToString(",") + (inner?.let { "; result: $it" } ?: "")).take(160)
        }

        private fun randomHex(n: Int) = ByteArray(n).also { random.nextBytes(it) }.hexUpper()

        internal fun parse(bytes: ByteArray): JsonObject = try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw TapoException.Unreachable(e)
        }

        /** Seconds left on a login lockout, if the reply reports one (pytapo `_lockoutSeconds`, python-kasa DEVICE_BLOCKED). */
        internal fun lockout(r: JsonObject): Int? {
            for (holder in listOf(r["error_info"], r["data"], (r["result"] as? JsonObject)?.get("data"))) {
                val o = holder as? JsonObject ?: continue
                val left = (o["sec_left"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (left != null && left > 0) return left
                if ((o["code"] as? JsonPrimitive)?.intOrNull == DEVICE_BLOCKED) throw TapoException.Locked(null)
            }
            return null
        }

        private const val DEVICE_BLOCKED = -40404

        /**
         * The result of the one method in a multipleRequest reply. A refused method is [TapoException.DeviceError]
         * with its code; anything that isn't such a reply is [TapoException.Unreachable].
         */
        fun unwrap(reply: JsonObject): JsonElement {
            val top = (reply["error_code"] as? JsonPrimitive)?.intOrNull ?: 0
            if (top != 0) throw TapoException.DeviceError(top)
            val responses = (reply["result"] as? JsonObject)?.get("responses") as? JsonArray
                ?: throw TapoException.Unreachable(IOException("No responses"))
            val first = responses.firstOrNull() as? JsonObject ?: throw TapoException.Unreachable(IOException("Empty responses"))
            val code = (first["error_code"] as? JsonPrimitive)?.intOrNull ?: 0
            if (code != 0) throw TapoException.DeviceError(code)
            return first["result"] ?: JsonNull
        }
    }
}
