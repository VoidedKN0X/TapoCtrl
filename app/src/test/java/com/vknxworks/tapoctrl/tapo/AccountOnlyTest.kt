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
        // Also none of the default camera passwords or setup passcodes other projects know of.
        for (bad in listOf("tp-link.net", "kasaSetup", "DefaultLogins", "authHashes(\"\"", "loginKind", "TPL075526460603", "YWRtaW4=", "VFBMMDc1NTI2NDYwNjAz", "MjEyMzJmMjk3YTU3YTVhNzQzODk0YTBlNGE4MDFmYzM=", "default_userpw", "GqY5o136oa4i6VprTlMW2DpVXxmfW8", "dGVzdA==")) {
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

    /** Cameras and older firmware: only values derived from the saved account, made only in AccountVault. */
    @Test fun cameraAndLegacyLoginsUseOnlyTheSavedAccount() {
        val src = File("src/main/java/com/vknxworks/tapoctrl")
        val files = src.walkTopDown().filter { it.extension == "kt" }.toList()
        for (maker in listOf("CameraSecret.of(", "LegacySecret.of(")) {
            val callers = files.filter { it.readText().contains(maker) && it.name != "CameraCrypto.kt" && it.name != "PassthroughCrypto.kt" }.map { it.name }
            assertTrue("$maker called from $callers", callers == listOf("AccountVault.kt"))
        }
        val camera = File(src, "tapo/CameraClient.kt").readText()
        val login = camera.substringAfter("private fun login()").substringBefore("private fun loginSecure(")
        // No account: stop before the first network call.
        assertTrue(login.indexOf("throw TapoException.NoAccount()") < login.indexOf("postLogin("))
        // encrypt_type 3: the camera proves it knows the password before anything derived from it is sent.
        val secure = camera.substringAfter("private fun loginSecure(").substringBefore("private fun loginHashed(")
        assertTrue(secure.indexOf("CameraLogin.matchConfirm(") in 0 until secure.indexOf("digest_passwd"))
        assertTrue(secure.contains("?: throw TapoException.WrongAccount()"))
        // TPAP: the camera's own proof is checked before the session is used.
        assertTrue(camera.contains("!proof.verify(devConfirm)) throw TapoException.WrongAccount()"))
        val legacy = File(src, "tapo/PassthroughClient.kt").readText()
        val legacyLogin = legacy.substringAfter("private fun login()").substringBefore("companion object")
        assertTrue(legacyLogin.indexOf("throw TapoException.NoAccount()") < legacyLogin.indexOf("handshake()"))
    }
}
