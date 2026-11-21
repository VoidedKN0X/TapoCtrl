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

    /** A transform TapoCtrl can't apply; the login stops and says so. */
    class UnsupportedTransform(val what: String) : Exception(what)

    /**
     * The SPAKE2+ credential for [passcode] and the camera's `extra_crypt` (pytapo `apply_extra_crypt`):
     * none: the passcode itself; password_shadow 2: SHA-1 hex; 5: SHA-256-crypt with the camera's salt.
     */
    fun tpapCredential(passcode: String, extraCrypt: JsonObject?): String {
        if (extraCrypt == null || extraCrypt.isEmpty()) return passcode
        val type = (extraCrypt["type"] as? JsonPrimitive)?.content?.lowercase()
        val params = extraCrypt["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (type != "password_shadow") throw UnsupportedTransform("extra_crypt ${type ?: "?"}")
        return when (val id = (params["passwd_id"] as? JsonPrimitive)?.intOrNull) {
            2 -> MessageDigest.getInstance("SHA-1").digest(passcode.toByteArray(Charsets.UTF_8)).hex()
            5 -> {
                val prefix = (params["passwd_prefix"] as? JsonPrimitive)?.content
                val parsed = prefix?.let { TpapCrypto.parseCryptPrefix(it) }
                val salt = parsed?.first ?: (params["passwd_salt"] as? JsonPrimitive)?.content
                    ?: throw UnsupportedTransform("password_shadow 5 without a salt")
                val rounds = parsed?.second ?: (params["passwd_rounds"] as? JsonPrimitive)?.intOrNull
                TpapCrypto.sha256Crypt(passcode.toByteArray(Charsets.UTF_8), salt, rounds)
            }
            else -> throw UnsupportedTransform("password_shadow ${id ?: "?"}")
        }
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
