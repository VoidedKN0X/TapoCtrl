package com.vknxworks.tapoctrl.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.vknxworks.tapoctrl.tapo.AuthHash
import com.vknxworks.tapoctrl.tapo.KlapCrypto
import com.vknxworks.tapoctrl.tapo.KlapVersion
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
 * Your Tapo account, as the devices need it: only the two KLAP hashes derived from email and password
 * (v2: SHA256(SHA1(email)+SHA1(password)), v1: MD5(MD5(email)+MD5(password))). The email and password themselves
 * are never stored. The hashes are encrypted with an AES-256-GCM key that lives in the phone's Android Keystore
 * and can't be taken out of it.
 */
class AccountVault(context: Context) {
    private val file = File(context.noBackupFilesDir, "account.bin")
    private var cache: List<AuthHash>? = null

    private val _saved = MutableStateFlow(file.exists())
    val saved: StateFlow<Boolean> = _saved

    /** Derives and saves the hashes; the password is only in memory for this call. */
    @Synchronized
    fun save(email: String, password: String): Boolean = try {
        val hashes = KlapCrypto.authHashes(email.trim(), password)
        val plain = hashes.first { it.version == KlapVersion.V2 }.hash + hashes.first { it.version == KlapVersion.V1 }.hash
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plain)
        plain.fill(0)
        val tmp = File(file.parentFile, "account.tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(file)) throw GeneralSecurityException("Couldn't save")
        cache = hashes
        _saved.value = true
        true
    } catch (e: Exception) {
        false
    }

    /** The saved hashes, v2 first; empty when none are saved or they can't be read. */
    @Synchronized
    fun hashes(): List<AuthHash> {
        cache?.let { return it }
        if (!file.exists()) return emptyList()
        return try {
            val blob = file.readBytes()
            if (blob.size < 1 + 12 + 48 + 16 || blob[0] != VERSION) return emptyList()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 1, 12))
            val plain = cipher.doFinal(blob, 13, blob.size - 13)
            listOf(AuthHash(KlapVersion.V2, plain.copyOfRange(0, 32)), AuthHash(KlapVersion.V1, plain.copyOfRange(32, 48)))
                .also { plain.fill(0); cache = it }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Forgets the account: deletes the file and the Keystore key. */
    @Synchronized
    fun clear() {
        cache = null
        file.delete()
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
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tapoctrl_account"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
    }
}
