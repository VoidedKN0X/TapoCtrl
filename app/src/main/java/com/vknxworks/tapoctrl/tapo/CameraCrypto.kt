package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The logins of Tapo cameras over HTTPS (port 443), as python-kasa (transports/sslaestransport.py), pytapo
 * (transport/pytapo, transport/tpap) and the tapo crate (aes_ssl_cipher.rs, tpap_spake2p.rs) do them. The camera
 * user is always "admin" with the Tapo account's password; TapoCtrl only keeps two hashes of that password
 * ([CameraSecret]): MD5 and SHA-256. Pure functions, unit-tested on the JVM.
 */

/** The two values derived from your Tapo password that cameras check. Kept encrypted in [com.vknxworks.tapoctrl.data.AccountVault]. */
class CameraSecret(val md5: ByteArray, val sha256: ByteArray) {
    /** MD5 and SHA-256 of the password as upper-case hex: the two password hashes the encrypt_type 3 login checks. */
    val md5Upper: String get() = md5.hexUpper()
    val sha256Upper: String get() = sha256.hexUpper()

    companion object {
        fun of(password: String): CameraSecret {
            val p = password.toByteArray(Charsets.UTF_8)
            return CameraSecret(MessageDigest.getInstance("MD5").digest(p), MessageDigest.getInstance("SHA-256").digest(p))
        }
    }
}

fun ByteArray.hexUpper(): String = joinToString("") { "%02X".format(it) }

/** Always the literal "admin": the camera's own user for logins with the Tapo account's password. */
internal const val CAMERA_USER = "admin"

internal object CameraLogin {
    fun sha256Upper(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).hexUpper()
    fun md5Lower(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).hex()

    // ---- encrypt_type 3 ("secure") login ----------------------------------------------------------------------

    /** What the camera sends as device_confirm when it knows the password with hash [pwdHash]. */
    fun deviceConfirm(cnonce: String, nonce: String, pwdHash: String): String = sha256Upper(cnonce + pwdHash + nonce) + nonce + cnonce

    /** The digest_passwd of the second login step. */
    fun digestPasswd(cnonce: String, nonce: String, pwdHash: String): String = sha256Upper(pwdHash + cnonce + nonce) + cnonce + nonce

    /**
     * Which of your password's hashes the camera knows (SHA-256 first, then MD5, as python-kasa checks them), or
     * null when it knows neither: then the login stops before your password's digest is ever sent.
     */
    fun matchConfirm(secret: CameraSecret, cnonce: String, nonce: String, deviceConfirm: String): String? =
        listOf(secret.sha256Upper, secret.md5Upper).firstOrNull {
            MessageDigest.isEqual(deviceConfirm(cnonce, nonce, it).toByteArray(), deviceConfirm.toByteArray())
        }

    // ---- TPAP --------------------------------------------------------------------------------------------------

    /** The user name sent in pake_register: MD5 of "admin" (lower-case hex), or SHA-256 upper-case when the camera says user_hash_type 1. */
    fun tpapUser(userHashType: Int?): String = if (userHashType == 1) sha256Upper(CAMERA_USER) else md5Lower(CAMERA_USER)

    /** The passcodes the Tapo app tries, in its order (pytapo `_passcodes`): MD5 (lower-case hex), then SHA-256 (upper-case hex). */
    fun tpapPasscodes(secret: CameraSecret): List<String> = listOf(secret.md5.hex(), secret.sha256Upper)

    /** A transform TapoCtrl can't apply; the login stops and says so ([what] names it, without any secret). */
    class UnsupportedTransform(val what: String) : Exception(what)

    /**
     * SHA-256-crypt rounds a camera may ask for. The C200 sends 5000; glibc allows up to 999 999 999, which would
     * keep a CPU core busy for minutes and can't be stopped part-way. More than this stops the login instead.
     */
    const val MAX_CRYPT_ROUNDS = 1_000_000

    private fun checkCryptRounds(rounds: Int?, extraCrypt: JsonObject) {
        if (rounds != null && rounds > MAX_CRYPT_ROUNDS) {
            throw UnsupportedTransform("password transform ${describeExtraCrypt(extraCrypt)}, rounds=$rounds")
        }
    }

    /**
     * The SPAKE2+ credential for [passcode] and the camera's `extra_crypt` (pytapo `apply_extra_crypt`, the
     * Tapo app's Spake2pExtraCryptBean as freeKC's tapo-v4-protocol documents it):
     * none: the passcode itself; password_shadow 1: MD5-crypt ("$1$"), 2: SHA-1 hex, 5: SHA-256-crypt ("$5$"),
     * each with the camera's salt. Anything else stops the login before pake_share with [UnsupportedTransform].
     */
    fun tpapCredential(passcode: String, extraCrypt: JsonObject?): String {
        if (extraCrypt == null || extraCrypt.isEmpty()) return passcode
        val type = (extraCrypt["type"] as? JsonPrimitive)?.content?.lowercase()
        val params = extraCrypt["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (type != "password_shadow") throw UnsupportedTransform("password transform ${describeExtraCrypt(extraCrypt)}")
        val prefix = (params["passwd_prefix"] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
        val saltParam = (params["passwd_salt"] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
        return when ((params["passwd_id"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()) {
            1 -> {
                val salt = cryptSalt(prefix, "$1$")?.first ?: saltParam?.takeIf { it.isNotEmpty() }
                    ?: throw UnsupportedTransform("password transform ${describeExtraCrypt(extraCrypt)}, no salt")
                TpapCrypto.md5Crypt(passcode.toByteArray(Charsets.UTF_8), salt)
            }
            2 -> MessageDigest.getInstance("SHA-1").digest(passcode.toByteArray(Charsets.UTF_8)).hex()
            5 -> {
                val fromPrefix = cryptSalt(prefix, "$5$")
                if (fromPrefix != null) {
                    checkCryptRounds(fromPrefix.second, extraCrypt)
                    // "$5$[rounds=N$]salt$", as the C200 sends it: rounds only when the prefix names them (pytapo, glibc).
                    TpapCrypto.sha256Crypt(passcode.toByteArray(Charsets.UTF_8), fromPrefix.first, fromPrefix.second, explicitRounds = fromPrefix.second != null)
                } else {
                    val salt = saltParam?.takeIf { it.isNotEmpty() }
                        ?: throw UnsupportedTransform("password transform ${describeExtraCrypt(extraCrypt)}, no salt")
                    val rounds = (params["passwd_rounds"] as? JsonPrimitive)?.content?.toIntOrNull()
                    checkCryptRounds(rounds, extraCrypt)
                    TpapCrypto.sha256Crypt(passcode.toByteArray(Charsets.UTF_8), salt, rounds)
                }
            }
            else -> throw UnsupportedTransform("password transform ${describeExtraCrypt(extraCrypt)}")
        }
    }

    /**
     * Salt and rounds from a crypt prefix such as "$5$rounds=5000$salt$" or "$5$salt$". A prefix without the
     * "$n$" marker is taken as the salt itself, as pytapo does. Null when there's no salt in it.
     */
    fun cryptSalt(prefix: String?, marker: String): Pair<String, Int?>? {
        if (prefix.isNullOrEmpty()) return null
        if (prefix.startsWith("$") && !prefix.startsWith(marker)) return null
        var rest = prefix.removePrefix(marker)
        var rounds: Int? = null
        if (rest.startsWith("rounds=")) {
            val end = rest.indexOf('$')
            if (end < 0) return null
            rounds = rest.substring(7, end).toIntOrNull() ?: return null
            rest = rest.substring(end + 1)
        }
        val salt = rest.substringBefore('$')
        return if (salt.isEmpty()) null else salt to rounds
    }

    /**
     * What a camera asked for in `extra_crypt`, for an error message: its type, passwd_id and which parameters it
     * sent (names only; salts and keys are left out).
     */
    fun describeExtraCrypt(extraCrypt: JsonObject): String {
        val type = (extraCrypt["type"] as? JsonPrimitive)?.content ?: "?"
        val params = extraCrypt["params"] as? JsonObject
        val id = (params?.get("passwd_id") as? JsonPrimitive)?.content
        val names = params?.keys?.sorted()?.joinToString(",").orEmpty()
        return buildString {
            append(type.take(40))
            if (id != null) append(" passwd_id=").append(id.take(10))
            if (names.isNotEmpty()) append(" [").append(names.take(120)).append(']')
        }
    }

    /** The PBKDF2 rounds the Tapo app assumes when pake_register doesn't name them (Spake2pRegisterResult). */
    const val DEFAULT_ITERATIONS = 10_000

    /** What a camera answered to pake_register, checked. */
    class TpapRegistration(val salt: ByteArray, val deviceShare: ByteArray, val deviceRandom: ByteArray, val iterations: Int, val extraCrypt: JsonObject?)

    /**
     * Reads a pake_register result. Throws [UnsupportedTransform] naming the field when something TapoCtrl can't
     * use is missing or out of range; nothing has been sent that depends on your password at that point.
     */
    fun tpapRegistration(result: JsonObject): TpapRegistration {
        fun text(k: String) = (result[k] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
        fun bytes(k: String): ByteArray {
            val v = text(k) ?: throw UnsupportedTransform("no $k (fields: ${result.keys.sorted().joinToString(",").take(160)})")
            return try {
                java.util.Base64.getDecoder().decode(v.trim())
            } catch (e: IllegalArgumentException) {
                throw UnsupportedTransform("$k isn't Base64")
            }
        }
        val rawIterations = text("iterations")
        val iterations = if (rawIterations == null) DEFAULT_ITERATIONS else rawIterations.trim().toIntOrNull()
            ?.takeIf { it in 1..Spake2p.MAX_ITERATIONS } ?: throw UnsupportedTransform("iterations=${rawIterations.take(20)}")
        val suite = text("cipher_suites")
        if (suite != null && suite.trim() != "1") throw UnsupportedTransform("cipher_suites=${suite.take(20)}")
        val encryption = text("encryption")
        if (encryption != null && !encryption.equals("aes_128_ccm", ignoreCase = true)) throw UnsupportedTransform("encryption=${encryption.take(40)}")
        val extra = result["extra_crypt"] as? JsonObject
        return TpapRegistration(bytes("dev_salt"), bytes("dev_share"), bytes("dev_random"), iterations, extra)
    }
}

/**
 * The session after an encrypt_type 3 login: AES-128-CBC (PKCS#7) with key and IV from the nonces and the password
 * hash, Base64 in "securePassthrough", and a Tapo_tag header over each request with its Seq.
 */
internal class CameraAesSession(cnonce: String, nonce: String, pwdHash: String, startSeq: Int) {
    private val hashedKey = CameraLogin.sha256Upper(cnonce + pwdHash + nonce)
    val key: ByteArray = TpapCrypto.sha256(("lsk" + cnonce + nonce + hashedKey).toByteArray()).copyOf(16)
    val iv: ByteArray = TpapCrypto.sha256(("ivb" + cnonce + nonce + hashedKey).toByteArray()).copyOf(16)
    private val tagPrefix = CameraLogin.sha256Upper(pwdHash + cnonce)
    var seq: Int = startSeq
        private set

    fun nextSeq(): Int = seq.also { seq += 1 }

    fun tag(body: String, seq: Int): String = CameraLogin.sha256Upper(tagPrefix + body + seq)

    fun encrypt(plain: String): String = AesCbc.encrypt(key, iv, plain.toByteArray(Charsets.UTF_8))

    fun decrypt(b64: String): String = AesCbc.decrypt(key, iv, b64).toString(Charsets.UTF_8)
}

/** AES-128-CBC with PKCS#7 padding and Base64, shared by the camera session and securePassthrough. */
internal object AesCbc {
    fun encrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): String {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return Base64.getEncoder().encodeToString(c.doFinal(plain))
    }

    fun decrypt(key: ByteArray, iv: ByteArray, b64: String): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(Base64.getDecoder().decode(b64))
    }
}
