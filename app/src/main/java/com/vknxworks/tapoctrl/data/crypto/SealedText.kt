package com.vknxworks.tapoctrl.data.crypto

import java.util.Base64

/**
 * A piece of text encrypted at rest, for a value kept inside a DataStore instead of in a file of its own:
 *
 *     "TAPOCTRL-REST1:" + Base64( [SealedFile] bytes )
 *
 * Text that doesn't start with that marker is ordinary text from before encryption (the device list's JSON
 * starts with `[`) and is read as it is, so both kinds work side by side.
 */
object SealedText {
    const val PREFIX = "TAPOCTRL-REST1:"

    /** True when [text] starts like sealed text. */
    fun isSealed(text: String): Boolean = text.startsWith(PREFIX)

    /**
     * [plain] sealed with [key], checked by opening it again and comparing, or null when that fails (the
     * caller then keeps the ordinary text).
     */
    fun seal(key: ByteArray, plain: String): String? = try {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val sealed = SealedFile.sealChecked(key, bytes)
        val text = if (sealed == null) null else PREFIX + Base64.getEncoder().encodeToString(sealed)
        if (text != null && open(key, text) == plain) text else null
    } catch (e: Exception) {
        null
    }

    /** Opens what [seal] made. Throws [SealedDataException] for a wrong key or damaged text. */
    fun open(key: ByteArray, text: String): String {
        if (!isSealed(text)) throw SealedDataException("not sealed text")
        val sealed = try {
            Base64.getDecoder().decode(text.substring(PREFIX.length))
        } catch (e: IllegalArgumentException) {
            throw SealedDataException("damaged sealed text", e)
        }
        return String(SealedFile.open(key, sealed), Charsets.UTF_8)
    }
}
