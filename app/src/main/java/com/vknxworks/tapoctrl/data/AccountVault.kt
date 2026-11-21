package com.vknxworks.tapoctrl.data

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.vknxworks.tapoctrl.tapo.AccountParts
import com.vknxworks.tapoctrl.tapo.AuthHash
import com.vknxworks.tapoctrl.tapo.CameraSecret
import com.vknxworks.tapoctrl.tapo.KlapCrypto
import com.vknxworks.tapoctrl.tapo.KlapVersion
import com.vknxworks.tapoctrl.tapo.LegacySecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Your Tapo account, as the devices need it. Only values derived from email and password are stored:
 * - the two KLAP hashes (v2: SHA256(SHA1(email)+SHA1(password)), v1: MD5(MD5(email)+MD5(password)));
 * - for cameras: MD5(password) and SHA256(password);
 * - for older plug and bulb firmware (securePassthrough): SHA1(email) and SHA1(password);
 * - the password itself only if you turned on "Keep the password for older firmware" (login version 1 of that
 *   protocol sends the password itself and can't work without it).
 * The email is never stored. Everything is encrypted with an AES-256-GCM key that lives in the phone's Android
 * Keystore and can't be taken out of it; the decrypted values are dropped from memory when the app lock locks.
 */
class AccountVault(context: Context) {
    private val file = File(context.noBackupFilesDir, "account.bin")
    private val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private var cache: Stored? = null

    private class Stored(val klap: List<AuthHash>, val camera: CameraSecret?, val legacy: LegacySecret?)

    private val _saved = MutableStateFlow(file.exists())
    val saved: StateFlow<Boolean> = _saved

    private val _keepsPassword = MutableStateFlow(false)
    /** Whether the saved account includes the password itself (only after [load] or [save]). */
    val keepsPassword: StateFlow<Boolean> = _keepsPassword

    private val _complete = MutableStateFlow(true)
    /** False for an account saved before 0.6: it has only the KLAP hashes, so cameras and older firmware need it entered again. */
    val complete: StateFlow<Boolean> = _complete

    /** Derives and saves the values; the password is only in memory for this call (unless [keepPassword]). */
    @Synchronized
    fun save(email: String, password: String, keepPassword: Boolean): Boolean = try {
        val e = email.trim()
        val hashes = KlapCrypto.authHashes(e, password)
        val camera = CameraSecret.of(password)
        val legacy = LegacySecret.of(e, password, keepPassword)
        val pw = legacy.password?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val plain = hashes.first { it.version == KlapVersion.V2 }.hash + hashes.first { it.version == KlapVersion.V1 }.hash +
            camera.md5 + camera.sha256 + legacy.emailSha1 + legacy.passwordSha1 +
            byteArrayOf((pw.size ushr 8).toByte(), pw.size.toByte()) + pw
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plain)
        plain.fill(0)
        pw.fill(0)
        val tmp = File(file.parentFile, "account.tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(file)) throw GeneralSecurityException("Couldn't save")
        cache = Stored(hashes, camera, legacy)
        _saved.value = true
        _keepsPassword.value = keepPassword
        _complete.value = true
        true
    } catch (e: Exception) {
        false
    }

    @Synchronized
    private fun load(): Stored? {
        cache?.let { return it }
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            if (blob.size < 1 + 12 + 48 + 16) return null
            val version = blob[0]
            if (version != VERSION_KLAP_ONLY && version != VERSION) return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 1, 12))
            val plain = cipher.doFinal(blob, 13, blob.size - 13)
            val klap = listOf(AuthHash(KlapVersion.V2, plain.copyOfRange(0, 32)), AuthHash(KlapVersion.V1, plain.copyOfRange(32, 48)))
            var camera: CameraSecret? = null
            var legacy: LegacySecret? = null
            if (version == VERSION && plain.size >= 48 + 16 + 32 + 20 + 20 + 2) {
                var at = 48
                fun take(n: Int) = plain.copyOfRange(at, at + n).also { at += n }
                camera = CameraSecret(take(16), take(32))
                val emailSha1 = take(20)
                val pwSha1 = take(20)
                val len = ((plain[at].toInt() and 0xFF) shl 8) or (plain[at + 1].toInt() and 0xFF)
                at += 2
                val pw = if (len > 0 && at + len <= plain.size) String(plain, at, len, Charsets.UTF_8) else null
                legacy = LegacySecret(emailSha1, pwSha1, pw)
            }
            plain.fill(0)
            Stored(klap, camera, legacy).also {
                cache = it
                _keepsPassword.value = legacy?.password != null
                _complete.value = camera != null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The saved KLAP hashes, v2 first; empty when none are saved or they can't be read. */
    fun hashes(): List<AuthHash> = load()?.klap ?: emptyList()

    /** What cameras and older firmware need; [AccountParts.saved] false when no account is saved. */
    fun parts(): AccountParts {
        val s = load()
        return AccountParts(saved = s != null, legacy = s?.legacy, camera = s?.camera)
    }

    /** Reads the saved account once (off the main thread), so [keepsPassword] and [complete] are known. */
    fun refreshFlags() {
        load()
    }

    /** Forgets the account: deletes the file and the Keystore key. */
    @Synchronized
    fun clear() {
        cache = null
        file.delete()
        _keepsPassword.value = false
        _complete.value = true
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
        }
        _saved.value = false
    }

    /** Drops the decrypted hashes from memory (when the app lock locks). */
    @Synchronized
    fun dropCache() {
        cache = null
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        // A new key goes into StrongBox (the phone's separate security chip) when there is one, otherwise into
        // the regular hardware-backed Keystore. A key made earlier keeps working as it is.
        fun make(strongBox: Boolean): SecretKey {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
            return gen.generateKey()
        }
        return if (hasStrongBox) runCatching { make(true) }.getOrElse { make(false) } else make(false)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tapoctrl_account"
        const val TRANSFORM = "AES/GCM/NoPadding"
        /** Before 0.6: only the two KLAP hashes. Still read. */
        const val VERSION_KLAP_ONLY: Byte = 1
        const val VERSION: Byte = 2
    }
}
