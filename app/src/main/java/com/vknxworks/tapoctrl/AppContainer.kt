package com.vknxworks.tapoctrl

import android.content.Context
import com.vknxworks.tapoctrl.data.AccountVault
import com.vknxworks.tapoctrl.data.DeviceRepository
import com.vknxworks.tapoctrl.data.lock.LockVault
import com.vknxworks.tapoctrl.data.settings.AppSettings
import com.vknxworks.tapoctrl.data.settings.SettingsRepository
import com.vknxworks.tapoctrl.ui.lock.AppLock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Background work that outlives a screen. A failure there must never close the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)

    /** The app lock's PIN and fingerprint key (the shared Ctrl series lock). */
    val vault = LockVault(context, "tapoctrl")

    /** Your Tapo account's KLAP hashes, encrypted with a Keystore key. */
    val account = AccountVault(context)

    val devices = DeviceRepository(context, appScope, account)

    init {
        AppLock.onLocked = { onLocked() }
    }

    suspend fun enableLock(pin: String) {
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) { vault.setUp(pin) }
    }

    fun disableLock() {
        vault.disable()
    }

    /**
     * "Forgot PIN?": the PIN can't be recovered, so TapoCtrl forgets your account hashes and your device list,
     * resets its settings and turns the lock off. Your devices themselves aren't changed.
     */
    suspend fun forgetEverything() {
        account.clear()
        devices.clearAll()
        settings.update { AppSettings() }
        vault.disable()
        AppLock.markUnlocked()
    }

    fun onLocked() {
        vault.close()
        account.dropCache()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
    }
}

val Context.container: AppContainer
    get() = (applicationContext as TapoCtrlApp).container
