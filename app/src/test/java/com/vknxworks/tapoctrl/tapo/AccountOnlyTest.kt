package com.vknxworks.tapoctrl.tapo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** TapoCtrl only ever checks the hashes of the user's own saved account: no built-in, blank or setup accounts. */
class AccountOnlyTest {
    private val local = ByteArray(16) { it.toByte() }
    private val remote = ByteArray(16) { (it + 16).toByte() }
    private val mine = KlapCrypto.authHashes("user@example.com", "hunter2")

    /** What a device set up with [email]/[password] would send in handshake1 (v2 and v1). */
    private fun replies(email: String, password: String) =
        KlapCrypto.authHashes(email, password).map { KlapCrypto.serverHash(it, local, remote) }

    @Test fun ownAccountMatchesAndIsTheStoredInstance() {
        val (v2, v1) = replies("user@example.com", "hunter2")
        assertSame(mine[0], KlapClient.chooseAuth(mine, local, remote, v2))
        assertSame(mine[1], KlapClient.chooseAuth(mine, local, remote, v1))
    }

    @Test fun blankAndSetupAccountsAreNeverAccepted() {
        val others = listOf("" to "", "test@tp-link.net" to "test", "kasa@tp-link.net" to "kasaSetup", "user@example.com" to "other")
        for ((u, p) in others) {
            for (reply in replies(u, p)) {
                try {
                    KlapClient.chooseAuth(mine, local, remote, reply)
                    fail("A device with the account '$u' was accepted")
                } catch (e: TapoException.WrongAccount) {
                    // expected: stop, no other attempts
                }
            }
        }
    }

    @Test fun noSavedAccountMeansNoLoginAtAll() {
        // Even a device on the blank account isn't accepted when nothing is saved.
        for (reply in replies("", "")) {
            try {
                KlapClient.chooseAuth(emptyList(), local, remote, reply)
                fail("Logged in without a saved account")
            } catch (e: TapoException.NoAccount) {
                // expected
            }
        }
    }

    /** Every hash checked during login comes from the stored list, and nothing else is consulted. */
    @Test fun onlyStoredHashesAreChecked() {
        val checked = mutableListOf<AuthHash>()
        val tracking = object : AbstractList<AuthHash>() {
            override val size get() = mine.size
            override fun get(index: Int) = mine[index].also { checked += it }
            override fun iterator() = mine.iterator().let { inner ->
                object : Iterator<AuthHash> {
                    override fun hasNext() = inner.hasNext()
                    override fun next() = inner.next().also { checked += it }
                }
            }
        }
        val wrong = replies("test@tp-link.net", "test")[0]
        try {
            KlapClient.chooseAuth(tracking, local, remote, wrong)
            fail()
        } catch (e: TapoException.WrongAccount) {
        }
        assertTrue(checked.isNotEmpty())
        assertTrue(checked.all { c -> mine.any { it === c } })
    }

    /** No credentials or fallback logins anywhere in the app's code; the handshake asks the account only. */
    @Test fun noBuiltInCredentialsInTheSources() {
        val src = File("src/main/java/com/vknxworks/tapoctrl")
        assertTrue(src.isDirectory)
        val all = src.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (bad in listOf("tp-link.net", "kasaSetup", "DefaultLogins", "authHashes(\"\"", "loginKind")) {
            assertFalse("Found $bad", all.contains(bad))
        }
        // authHashes is only called with the user's input in AccountVault.save.
        val callers = src.walkTopDown().filter { it.extension == "kt" && it.readText().contains("KlapCrypto.authHashes(") }.map { it.name }.toList()
        assertTrue("authHashes called from $callers", callers == listOf("AccountVault.kt"))
        val client = File(src, "tapo/KlapClient.kt").readText()
        val handshake = client.substringAfter("private fun handshake()").substringBefore("companion object")
        assertTrue(handshake.contains("chooseAuth(account, local, remote, server)"))
        assertFalse(handshake.contains("KlapCrypto.match("))
        // No saved account: stop before the first network call.
        assertTrue(handshake.indexOf("throw TapoException.NoAccount()") < handshake.indexOf("LanHttp.post"))
    }
}
