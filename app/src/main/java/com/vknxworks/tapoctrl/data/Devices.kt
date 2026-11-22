package com.vknxworks.tapoctrl.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.tapoctrl.data.crypto.DataKeyStore
import com.vknxworks.tapoctrl.data.crypto.SealedText
import com.vknxworks.tapoctrl.net.LanAddress
import com.vknxworks.tapoctrl.tapo.AutoOffConfig
import com.vknxworks.tapoctrl.tapo.ChildInfo
import com.vknxworks.tapoctrl.tapo.ChildReply
import com.vknxworks.tapoctrl.tapo.Components
import com.vknxworks.tapoctrl.tapo.DeviceInfo
import com.vknxworks.tapoctrl.tapo.DeviceUsage
import com.vknxworks.tapoctrl.tapo.BulbEffect
import com.vknxworks.tapoctrl.tapo.LedInfo
import com.vknxworks.tapoctrl.tapo.LightPreset
import com.vknxworks.tapoctrl.tapo.StripEffects
import com.vknxworks.tapoctrl.tapo.DeviceReads
import com.vknxworks.tapoctrl.tapo.Discovery
import com.vknxworks.tapoctrl.tapo.TapoMethods
import com.vknxworks.tapoctrl.tapo.DownloadState
import com.vknxworks.tapoctrl.tapo.EnergyRange
import com.vknxworks.tapoctrl.tapo.EnergySeries
import com.vknxworks.tapoctrl.tapo.EnergyUsage
import com.vknxworks.tapoctrl.tapo.FirmwareInfo
import com.vknxworks.tapoctrl.net.PinnedTls
import com.vknxworks.tapoctrl.tapo.Camera
import com.vknxworks.tapoctrl.tapo.CameraClient
import com.vknxworks.tapoctrl.tapo.CameraStatus
import com.vknxworks.tapoctrl.tapo.SmartClient
import com.vknxworks.tapoctrl.tapo.TapoException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

private val Context.deviceStore by preferencesDataStore(name = "devices")
private val DEVICES = stringPreferencesKey("devices_json")
/** A sealed device list that couldn't be opened and was set aside ("Start empty"); never read back by the app. */
private val DEVICES_UNREADABLE = stringPreferencesKey("devices_json_unreadable")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A device you added. Only what's needed to find it again and show it before it answers. */
@Serializable
data class SavedDevice(
    val id: String,
    val ip: String,
    val name: String = "",
    val model: String = "",
    val type: String = "",
    val mac: String = "",
    /** The room you put it in ("Living room"), or empty. The list's order is your own order. */
    val room: String = "",
    /**
     * Cameras: the SHA-256 fingerprint of the camera's own certificate, as you confirmed it when adding the
     * camera. Only that certificate is trusted for it.
     */
    val certSha256: String = "",
    /** Older (securePassthrough) firmware: the login version the device announced in a scan (2: hashes only); 0 when unknown. */
    val loginVersion: Int = 0,
) {
    /** A Tapo camera (SMART.IPCAMERA): HTTPS with its own certificate, and its own requests. */
    val isCamera: Boolean get() = type.contains("CAMERA", ignoreCase = true)
}

/** What TapoCtrl currently knows about a device. */
data class DeviceState(
    val info: DeviceInfo? = null,
    val error: TapoException? = null,
    val busy: Boolean = false,
    val energy: EnergyUsage? = null,
    /** null: not asked yet; false: the device doesn't measure energy. */
    val energySupported: Boolean? = null,
    val updatedAt: Long = 0,
    /** Feature ids from component_nego; null until asked, empty when the device doesn't say. */
    val components: Set<String>? = null,
    /** Power-strip sockets, or a hub's sensors and switches; null when it has none or wasn't asked yet. */
    val children: List<ChildInfo>? = null,
    val led: LedInfo? = null,
    val autoOff: AutoOffConfig? = null,
    val usage: DeviceUsage? = null,
    /** Light presets saved on the device, and a colour bulb's built-in effects. */
    val presets: List<LightPreset>? = null,
    val bulbEffects: List<BulbEffect>? = null,
    /** Cameras: model, name, privacy mode, features and saved positions. */
    val camera: CameraStatus? = null,
    /** True when the device has older firmware and is reached with securePassthrough instead of KLAP. */
    val legacyProtocol: Boolean = false,
)

sealed interface AddResult {
    data class Added(val device: SavedDevice) : AddResult
    data object AlreadyAdded : AddResult
    data object NotLocal : AddResult
    data object NoAnswer : AddResult
    /** The scan said there's a camera at [ip], but nothing answered on HTTPS (port 443) there. */
    data class CameraNoAnswer(val ip: String) : AddResult
    /**
     * A camera: its certificate's fingerprint, for you to confirm before anything is sent to it. Then
     * [DeviceRepository.addCamera].
     */
    data class ConfirmCertificate(val ip: String, val fingerprint: String, val hint: Found?) : AddResult
}

/**
 * The device list as it was last read from the DataStore. [unreadable]: it is encrypted and can't be opened
 * (the key in the phone's secure storage is gone or changed); the stored value is then left exactly as it is.
 */
private class StoredDevices(val list: List<SavedDevice>, val unreadable: Boolean = false)

/**
 * The saved device list and everything asked of the devices.
 *
 * The list is one JSON text in the "devices" DataStore. It is encrypted at rest with the app's data key
 * ([DataKeyStore], [SealedText]); a list from before encryption is still read, and is then replaced by its
 * encrypted copy. When there is no data key to be had, the list is read and written as ordinary text, as before.
 */
class DeviceRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val account: AccountVault,
    private val keys: DataKeyStore,
) {
    /** What's stored; null until the first read. */
    private val stored = MutableStateFlow<StoredDevices?>(null)

    private val _devices = MutableStateFlow<List<SavedDevice>>(emptyList())
    val devices: StateFlow<List<SavedDevice>> = _devices

    private val _loaded = MutableStateFlow(false)
    /** False until the saved list was read (the key that opens it is asked from the secure hardware first). */
    val loaded: StateFlow<Boolean> = _loaded

    private val _unreadable = MutableStateFlow(false)
    /**
     * The saved list is encrypted and can't be opened. It is left exactly as it is, nothing is saved while this
     * is true, and the app shows what happened instead of an empty list.
     */
    val unreadable: StateFlow<Boolean> = _unreadable

    init {
        scope.launch {
            try {
                // The key first, off the main thread (it asks the secure hardware, and is made at the first
                // start), so opening and sealing the list below is plain arithmetic with a key in memory.
                withContext(Dispatchers.IO) { keys.get(create = true) }
                var sealTried = false
                context.deviceStore.data
                    .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                    .collect { p ->
                        val raw = p[DEVICES]
                        publish(read(raw))
                        // A list from before encryption: replace it by its encrypted copy, once per start.
                        if (!sealTried && raw != null && !SealedText.isSealed(raw)) {
                            sealTried = true
                            scope.launch { sealStored() }
                        }
                    }
            } finally {
                // Nothing waits for ever on a list that couldn't be read at all.
                if (stored.value == null) publish(StoredDevices(emptyList()))
            }
        }
    }

    private fun publish(s: StoredDevices) {
        _devices.value = s.list
        _unreadable.value = s.unreadable
        stored.value = s
        _loaded.value = true
    }

    /**
     * Reads a stored value. Only after the key was asked for in [init], so [DataKeyStore.get] answers from
     * memory here. Text that isn't a device list reads as an empty list, as before.
     */
    private fun read(raw: String?): StoredDevices {
        if (raw == null) return StoredDevices(emptyList())
        val plain = if (SealedText.isSealed(raw)) {
            val key = keys.get(create = false) ?: return StoredDevices(emptyList(), unreadable = true)
            try {
                SealedText.open(key, raw)
            } catch (e: Exception) {
                return StoredDevices(emptyList(), unreadable = true)
            }
        } else {
            raw
        }
        return StoredDevices(runCatching { json.decodeFromString<List<SavedDevice>>(plain) }.getOrNull() ?: emptyList())
    }

    /** [plain] as it goes into the DataStore: encrypted when there's a data key, ordinary text otherwise. */
    private fun seal(plain: String): String = keys.get(create = false)?.let { SealedText.seal(it, plain) } ?: plain

    /**
     * Replaces an ordinary stored list by its encrypted copy: the same text, sealed, checked by opening it
     * again ([SealedText.seal]), and swapped in by the DataStore in one step (it writes a new file, syncs it and
     * renames it over the old one). If anything fails the ordinary text simply stays in use.
     */
    private suspend fun sealStored() {
        try {
            context.deviceStore.edit { p ->
                val raw = p[DEVICES] ?: return@edit
                if (SealedText.isSealed(raw)) return@edit
                val sealed = seal(raw)
                if (sealed != raw) p[DEVICES] = sealed
            }
        } catch (e: IOException) {
        }
    }

    /** The saved list, once it was read. */
    private suspend fun saved(): List<SavedDevice> = stored.filterNotNull().first().list

    /** Tries to open the saved list again, after [unreadable]. */
    fun retry() {
        scope.launch {
            if (!_unreadable.value) return@launch
            withContext(Dispatchers.IO) {
                keys.reset()
                keys.get(create = false)
            }
            try {
                publish(read(context.deviceStore.data.first()[DEVICES]))
            } catch (e: IOException) {
            }
        }
    }

    /**
     * Gives up on a saved list that can't be opened and starts empty. With [keep] the sealed text is set aside
     * under another name in the DataStore (and the key file as `data.key.unreadable`) instead of deleted. Does
     * nothing unless the list is [unreadable].
     */
    suspend fun startEmpty(keep: Boolean) {
        if (!_unreadable.value) return
        try {
            context.deviceStore.edit { p ->
                val raw = p[DEVICES] ?: return@edit
                // Only what still can't be opened.
                if (!read(raw).unreadable) return@edit
                if (keep) p[DEVICES_UNREADABLE] = raw
                p.remove(DEVICES)
            }
        } catch (e: IOException) {
            return
        }
        // A key that still works stays; one that's gone makes way for a new one.
        withContext(Dispatchers.IO) {
            if (keys.get(create = false) == null) {
                keys.discard(keep)
                keys.get(create = true)
            }
        }
    }

    private val _states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val states: StateFlow<Map<String, DeviceState>> = _states

    private val clients = HashMap<String, SmartClient>()
    private val cameras = HashMap<String, CameraClient>()

    /** Plugs, bulbs, strips, switches and hubs: KLAP, or securePassthrough on older firmware (picked by itself). */
    private fun client(ip: String): SmartClient? {
        val addr = LanAddress.parse(ip) ?: return null
        synchronized(clients) {
            return clients.getOrPut(ip) {
                SmartClient(addr, { account.hashes() }, { account.parts() }) {
                    devices.value.firstOrNull { it.ip == ip }?.loginVersion?.takeIf { it > 0 }
                }
            }
        }
    }

    /** Cameras: HTTPS trusting only the certificate you confirmed for this address. */
    private fun cameraClient(ip: String): CameraClient? {
        val addr = LanAddress.parse(ip) ?: return null
        synchronized(cameras) {
            return cameras.getOrPut(ip) {
                CameraClient(addr, { devices.value.firstOrNull { it.ip == ip && it.isCamera }?.certSha256 }) { account.parts() }
            }
        }
    }

    /** Forget every open session (after the account changes). */
    fun resetSessions() {
        synchronized(clients) { clients.values.forEach { it.reset() }; clients.clear() }
        synchronized(cameras) { cameras.values.forEach { it.reset() }; cameras.clear() }
        _states.update { m -> m.mapValues { it.value.copy(error = null) } }
    }

    private suspend fun save(transform: (List<SavedDevice>) -> List<SavedDevice>) {
        // After the first read, so the key is in memory (or known to be missing) by now.
        saved()
        var written: List<SavedDevice>? = null
        try {
            context.deviceStore.edit { p ->
                val now = read(p[DEVICES])
                // Never over a list that couldn't be opened: it stays until it opens again or is given up.
                if (now.unreadable) return@edit
                val next = transform(now.list)
                p[DEVICES] = seal(json.encodeToString(next))
                written = next
            }
        } catch (e: IOException) {
            return
        }
        // Shown at once, so what's asked right after a save (a refresh of a device just added) finds it.
        written?.let { publish(StoredDevices(it)) }
    }

    private fun setState(id: String, f: (DeviceState) -> DeviceState) {
        _states.update { it + (id to f(it[id] ?: DeviceState())) }
    }

    private suspend fun current(id: String): SavedDevice? =
        devices.value.firstOrNull { it.id == id } ?: saved().firstOrNull { it.id == id }

    /**
     * Adds a device by address. It's added when it answers, even if your account doesn't fit yet. A camera (from
     * the scan, or an address that answers only on HTTPS) first returns [AddResult.ConfirmCertificate].
     */
    suspend fun add(ip: String, hint: Found? = null): AddResult {
        val addr = LanAddress.parse(ip) ?: return AddResult.NotLocal
        val text = LanAddress.text(addr)
        saved().firstOrNull { it.ip == text }?.let { return AddResult.AlreadyAdded }
        if (hint?.camera == true) return cameraCertificate(text, hint) ?: AddResult.CameraNoAnswer(text)
        val c = client(text)!!
        val info = try {
            DeviceReads.info(c)
        } catch (e: TapoException.Unreachable) {
            // No plug or bulb there: a camera answers only on HTTPS.
            return if (hint == null) cameraCertificate(text, null) ?: AddResult.NoAnswer else AddResult.NoAnswer
        } catch (e: TapoException.NoAccount) {
            // No account yet, so nothing was sent. Typed by address: if it isn't a KLAP device (the probe sends
            // only a random seed), it may be a camera; offer its certificate instead of adding it as a plug.
            if (hint == null && !withContext(Dispatchers.IO) { Discovery.probeKlap(addr, connectMs = 1500, readMs = 2500) }) {
                cameraCertificate(text, null)?.let { return it }
            }
            null
        } catch (e: TapoException) {
            null
        }
        if (info?.mac != null && devices.value.any { it.mac.isNotEmpty() && it.mac.equals(info.mac, true) }) {
            // Same device at a new address: just move it.
            val old = devices.value.first { it.mac.equals(info.mac, true) }
            save { list -> list.map { if (it.id == old.id) it.copy(ip = text) else it } }
            setState(old.id) { DeviceState(info = info, updatedAt = System.currentTimeMillis()) }
            return AddResult.Added(old.copy(ip = text))
        }
        val d = SavedDevice(
            id = UUID.randomUUID().toString(),
            ip = text,
            name = info?.nickname.orEmpty(),
            model = info?.model ?: hint?.model.orEmpty(),
            type = info?.type ?: hint?.type.orEmpty(),
            mac = info?.mac ?: hint?.mac.orEmpty(),
            loginVersion = hint?.loginVersion ?: 0,
        )
        save { it + d }
        setState(d.id) { DeviceState(info = info, updatedAt = if (info != null) System.currentTimeMillis() else 0) }
        if (info == null) refresh(d.id)
        return AddResult.Added(d)
    }

    /** Reads the certificate a camera presents (the TLS handshake stops there; nothing is sent). Null: no answer. */
    private suspend fun cameraCertificate(ip: String, hint: Found?): AddResult.ConfirmCertificate? = withContext(Dispatchers.IO) {
        val addr = LanAddress.parse(ip) ?: return@withContext null
        try {
            AddResult.ConfirmCertificate(ip, PinnedTls.fingerprint(PinnedTls.fetchLeaf(addr)), hint)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Adds a camera after you confirmed its certificate's [fingerprint]: saved with that fingerprint, then read
     * (getDeviceInfo, privacy mode, features). Only reads.
     */
    suspend fun addCamera(ip: String, fingerprint: String, hint: Found?): AddResult {
        val addr = LanAddress.parse(ip) ?: return AddResult.NotLocal
        val text = LanAddress.text(addr)
        saved().firstOrNull { it.ip == text }?.let { return AddResult.AlreadyAdded }
        val mac = hint?.mac.orEmpty()
        val old = if (mac.isNotEmpty()) saved().firstOrNull { it.isCamera && it.mac.isNotEmpty() && normMac(it.mac) == normMac(mac) } else null
        if (old != null) {
            // The same camera at a new address: move it, with the certificate you just confirmed.
            save { list -> list.map { if (it.id == old.id) it.copy(ip = text, certSha256 = fingerprint) else it } }
            synchronized(cameras) { cameras.remove(old.ip)?.reset() }
            refresh(old.id)
            return AddResult.Added(old.copy(ip = text, certSha256 = fingerprint))
        }
        val d = SavedDevice(
            id = UUID.randomUUID().toString(),
            ip = text,
            model = hint?.model.orEmpty(),
            type = hint?.type?.takeIf { it.contains("CAMERA", true) } ?: CAMERA_TYPE,
            mac = mac,
            certSha256 = fingerprint,
        )
        save { it + d }
        synchronized(cameras) { cameras.remove(text)?.reset() }
        refresh(d.id)
        return AddResult.Added(d)
    }

    suspend fun remove(id: String) {
        save { list -> list.filterNot { it.id == id } }
        _states.update { it - id }
    }

    suspend fun clearAll() {
        // A list that couldn't even be opened goes too ("Forgot PIN?" deletes; nothing is set aside).
        saved()
        startEmpty(keep = false)
        save { emptyList() }
        _states.value = emptyMap()
        resetSessions()
    }

    /** Asks every device for its state at once. */
    suspend fun refreshAll() = coroutineScope {
        saved().map { d -> async { refresh(d.id) } }.awaitAll()
    }

    suspend fun refresh(id: String) {
        val d = current(id) ?: return
        if (d.isCamera) return refreshCamera(d)
        val c = client(d.ip) ?: return
        setState(id) { it.copy(busy = true) }
        try {
            val info = DeviceReads.info(c)
            setState(id) { it.copy(info = info, error = null, busy = false, updatedAt = System.currentTimeMillis(), legacyProtocol = c.usesLegacy) }
            // Which features it has: asked once (component_nego), then its sockets or sensors with each refresh.
            val components = _states.value[id]?.components ?: try {
                DeviceReads.components(c)
            } catch (e: TapoException.DeviceError) {
                emptySet()
            }
            setState(id) { it.copy(components = components) }
            if (Components.CHILD_DEVICE in components) {
                try {
                    val children = DeviceReads.children(c)
                    setState(id) { it.copy(children = children) }
                } catch (e: TapoException.DeviceError) {
                    setState(id) { it.copy(children = emptyList()) }
                }
            }
            // Keep the saved name, model and MAC in step with the device.
            if (info.nickname != d.name || info.model != d.model || (info.mac ?: "") != d.mac || info.type != d.type) {
                save { list -> list.map { if (it.id == id) it.copy(name = info.nickname, model = info.model, type = info.type, mac = info.mac ?: it.mac) else it } }
            }
        } catch (e: TapoException) {
            setState(id) { it.copy(error = e, busy = false) }
        }
    }

    /** A camera's info, privacy mode, features and saved positions. Only reads. */
    private suspend fun refreshCamera(d: SavedDevice) {
        val c = cameraClient(d.ip) ?: return
        setState(d.id) { it.copy(busy = true) }
        try {
            val cam = DeviceReads.camera(c, _states.value[d.id]?.camera?.components?.takeIf { it.isNotEmpty() })
            setState(d.id) { it.copy(camera = cam, error = null, busy = false, updatedAt = System.currentTimeMillis()) }
            val mac = cam.mac.orEmpty()
            if ((cam.name.isNotBlank() && cam.name != d.name) || (cam.model.isNotBlank() && cam.model != d.model) || (mac.isNotBlank() && mac != d.mac)) {
                save { list -> list.map { if (it.id == d.id) it.copy(name = cam.name.ifBlank { it.name }, model = cam.model.ifBlank { it.model }, mac = mac.ifBlank { it.mac }) else it } }
            }
        } catch (e: TapoException) {
            setState(d.id) { it.copy(error = e, busy = false) }
        }
    }

    /**
     * Sends set_device_info, then shows the new state. Returns the error, or null when it worked.
     * Called only from a tap on that device's own control (power button, slider, rename); nothing in TapoCtrl
     * changes a device on its own.
     */
    suspend fun set(id: String, params: JsonObject): TapoException? {
        val d = current(id) ?: return null
        val c = client(d.ip) ?: return TapoException.NotLocal()
        return try {
            c.call(TapoMethods.SET_DEVICE_INFO, params)
            refresh(id)
            null
        } catch (e: TapoException) {
            setState(id) { it.copy(error = e) }
            e
        }
    }

    suspend fun setOn(id: String, on: Boolean): TapoException? {
        // Show the change at once; the refresh after it corrects it if the device disagrees.
        setState(id) { s -> s.copy(info = s.info?.copy(on = on)) }
        return set(id, buildJsonObject { put("device_on", on) })
    }

    suspend fun rename(id: String, name: String): TapoException? {
        val e = set(id, buildJsonObject { put("nickname", DeviceInfo.toB64(name)) })
        if (e == null) save { list -> list.map { if (it.id == id) it.copy(name = name) else it } }
        return e
    }

    /**
     * The device's own settings and usage time, for its page: the status light, auto-off and how long it was on.
     * Only reads, and only what component_nego says the device has.
     */
    suspend fun refreshSettings(id: String) {
        val d = current(id) ?: return
        val c = client(d.ip) ?: return
        val components = _states.value[id]?.components ?: return
        suspend fun <T> readOrNull(block: suspend () -> T): T? = try {
            block()
        } catch (e: TapoException) {
            null
        }
        val led = if (Components.LED in components) readOrNull { DeviceReads.led(c) } else null
        val autoOff = if (Components.AUTO_OFF in components) readOrNull { DeviceReads.autoOff(c) } else null
        val usage = if (Components.DEVICE_USAGE in components) readOrNull { DeviceReads.usage(c) } else null
        val presets = if (StripEffects.PRESET_COMPONENT in components) readOrNull { DeviceReads.presets(c) } else null
        val bulbEffects = if (StripEffects.BULB_COMPONENT in components) readOrNull { DeviceReads.bulbEffects(c) } else null
        setState(id) {
            it.copy(
                led = led ?: it.led, autoOff = autoOff ?: it.autoOff, usage = usage ?: it.usage,
                presets = presets ?: it.presets, bulbEffects = bulbEffects ?: it.bulbEffects,
            )
        }
    }

    /**
     * Switches one socket of a power strip (or one switch on a hub): set_device_info for that child, sent through
     * its parent with control_child. Only from a tap on that socket's switch. Returns the error, or null.
     */
    suspend fun setChildOn(id: String, childId: String, on: Boolean): TapoException? {
        val d = current(id) ?: return null
        val c = client(d.ip) ?: return TapoException.NotLocal()
        setState(id) { s -> s.copy(children = s.children?.map { if (it.deviceId == childId) it.copy(on = on) else it }) }
        return try {
            val reply = c.call(TapoMethods.CONTROL_CHILD, buildJsonObject {
                put("device_id", childId)
                put("requestData", buildJsonObject {
                    put("method", TapoMethods.SET_DEVICE_INFO)
                    put("params", buildJsonObject { put("device_on", on) })
                })
            })
            ChildReply.check(reply)
            refresh(id)
            null
        } catch (e: TapoException) {
            refresh(id)
            e
        }
    }

    /** The built-in strip effects, read once from the app's own files. */
    val stripEffects: List<JsonObject> by lazy {
        runCatching { context.assets.open(StripEffects.ASSET).use { String(it.readBytes(), Charsets.UTF_8) } }
            .map { StripEffects.parse(it) }.getOrDefault(emptyList())
    }

    /** Applies a saved light preset: set_device_info with its brightness and colour. Only from a tap on that preset. */
    suspend fun applyPreset(id: String, p: LightPreset): TapoException? = set(id, buildJsonObject {
        put("brightness", p.brightness)
        if (p.hue != null && p.saturation != null && (p.colorTemp == null || p.colorTemp == 0)) {
            put("hue", p.hue)
            put("saturation", p.saturation)
            put("color_temp", 0)
        } else if (p.colorTemp != null) {
            put("color_temp", p.colorTemp)
        }
    })

    /**
     * A light strip effect on ([name]) or off (null): set_lighting_effect with python-kasa's definition of it, at
     * the strip's brightness. Only from a tap on that effect.
     */
    suspend fun setStripEffect(id: String, name: String?): TapoException? {
        val d = current(id) ?: return null
        val c = client(d.ip) ?: return TapoException.NotLocal()
        val info = _states.value[id]?.info
        val effects = stripEffects
        val chosen = if (name != null) effects.firstOrNull { StripEffects.name(it) == name } else
            effects.firstOrNull { StripEffects.name(it) == info?.effect?.name } ?: effects.firstOrNull()
        chosen ?: return null
        return try {
            c.call(TapoMethods.SET_LIGHTING_EFFECT, StripEffects.request(chosen, on = name != null, brightness = info?.brightness))
            refresh(id)
            null
        } catch (e: TapoException) {
            e
        }
    }

    /** A colour bulb's effect on ([effectId]) or off (null). Only from a tap on that effect. */
    suspend fun setBulbEffect(id: String, effectId: String?): TapoException? {
        val d = current(id) ?: return null
        val c = client(d.ip) ?: return TapoException.NotLocal()
        return try {
            c.call(TapoMethods.SET_DYNAMIC_LIGHT_EFFECT_RULE_ENABLE, buildJsonObject {
                put("enable", effectId != null)
                if (effectId != null) put("id", effectId)
            })
            refresh(id)
            null
        } catch (e: TapoException) {
            e
        }
    }

    /** The status light on or off (set_led_info with the rule changed). Only from a tap on that switch. */
    suspend fun setLed(id: String, on: Boolean): TapoException? {
        val d = current(id) ?: return null
        val c = client(d.ip) ?: return TapoException.NotLocal()
        val raw = _states.value[id]?.led?.raw ?: return null
        val params = JsonObject(raw + ("led_rule" to kotlinx.serialization.json.JsonPrimitive(if (on) "always" else "never")))
        return try {
            c.call(TapoMethods.SET_LED_INFO, params)
            refreshSettings(id)
            null
        } catch (e: TapoException) {
            e
        }
    }

    /** Auto-off on or off, and after how many minutes. Only from a tap on that setting. */
    suspend fun setAutoOff(id: String, enabled: Boolean, minutes: Int): TapoException? {
        val d = current(id) ?: return null
        val c = client(d.ip) ?: return TapoException.NotLocal()
        return try {
            c.call(TapoMethods.SET_AUTO_OFF_CONFIG, buildJsonObject {
                put("enable", enabled)
                put("delay_min", minutes.coerceIn(1, 24 * 60))
            })
            refreshSettings(id)
            null
        } catch (e: TapoException) {
            e
        }
    }

    /** Sends one camera command. Returns the error, or null when it worked. */
    private suspend fun cameraCommand(id: String, method: String, params: JsonObject): TapoException? {
        val d = current(id)?.takeIf { it.isCamera } ?: return null
        val c = cameraClient(d.ip) ?: return TapoException.NotLocal()
        return try {
            c.call(method, params)
            null
        } catch (e: TapoException) {
            e
        }
    }

    /** Privacy mode on or off (setLensMaskConfig). Only from a tap on the camera's privacy control. */
    suspend fun setPrivacy(id: String, on: Boolean): TapoException? {
        setState(id) { s -> s.copy(camera = s.camera?.copy(privacy = on)) }
        val e = cameraCommand(id, TapoMethods.CAM_SET_LENS_MASK, Camera.setLensMaskParams(on))
        refresh(id)
        return e
    }

    /** Pans and tilts by one step (motorMove). Only from a tap on an arrow. */
    suspend fun moveCamera(id: String, x: Int, y: Int): TapoException? =
        cameraCommand(id, TapoMethods.CAM_MOTOR_MOVE, Camera.moveParams(x, y))

    /** Turns to a saved position (motorMoveToPreset). Only from a tap on that position. */
    suspend fun gotoCameraPreset(id: String, presetId: String): TapoException? =
        cameraCommand(id, TapoMethods.CAM_MOTOR_TO_PRESET, Camera.gotoPresetParams(presetId))

    /** Restarts the camera (rebootDevice). Only after the confirmation dialog; never sent twice. */
    suspend fun rebootCamera(id: String): TapoException? {
        val e = cameraCommand(id, TapoMethods.CAM_REBOOT, Camera.rebootParams())
        val ip = current(id)?.ip
        if (ip != null) synchronized(cameras) { cameras[ip]?.reset() }
        return e
    }

    /** Puts a device in a room (empty: no room). Only on this phone; nothing is sent to the device. */
    suspend fun setRoom(id: String, room: String) {
        val clean = room.filterNot { it.isISOControl() }.trim().take(40)
        save { list -> list.map { if (it.id == id) it.copy(room = clean) else it } }
    }

    /** Settings > Import settings: the device list merged with a settings file's devices (see SettingsFile.merge). */
    suspend fun importDevices(transform: (List<SavedDevice>) -> List<SavedDevice>) {
        save(transform)
    }

    /** Moves a device one place up ([by] < 0) or down in your own order. Only on this phone. */
    suspend fun move(id: String, by: Int) {
        save { list ->
            val from = list.indexOfFirst { it.id == id }
            val to = from + by
            if (from < 0 || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    /** Energy for plugs that measure it; remembers when a device doesn't. */
    suspend fun refreshEnergy(id: String) {
        val d = current(id) ?: return
        val c = client(d.ip) ?: return
        if (_states.value[id]?.energySupported == false) return
        try {
            val usage = DeviceReads.energy(c)
            setState(id) { it.copy(energy = usage, energySupported = true) }
        } catch (e: TapoException.DeviceError) {
            setState(id) { it.copy(energySupported = false) }
        } catch (e: TapoException) {
            // Unreachable for now; try again next time.
        }
    }

    /** An energy graph for a plug that measures energy. Only reads (get_energy_data, get_energy_usage). */
    suspend fun energyHistory(id: String, range: EnergyRange): EnergySeries {
        val c = current(id)?.let { client(it.ip) } ?: throw TapoException.NotLocal()
        return DeviceReads.energyHistory(c, range, java.time.LocalDateTime.now())
    }

    suspend fun latestFirmware(id: String): FirmwareInfo {
        val c = current(id)?.let { client(it.ip) } ?: throw TapoException.NotLocal()
        return DeviceReads.latestFirmware(c)
    }

    /**
     * Tells the device to download and install the update it found itself (fw_download takes no parameters:
     * the device fetches TP-Link's signed firmware on its own; TapoCtrl never passes an address).
     */
    suspend fun startFirmwareDownload(id: String) {
        val c = current(id)?.let { client(it.ip) } ?: throw TapoException.NotLocal()
        c.call(TapoMethods.FW_DOWNLOAD, null)
    }

    suspend fun downloadState(id: String): DownloadState {
        val c = current(id)?.let { client(it.ip) } ?: throw TapoException.NotLocal()
        return DeviceReads.downloadState(c)
    }

    /** get_device_info without touching the shown state (used while a device reboots). */
    suspend fun probeInfo(id: String): DeviceInfo? {
        val c = current(id)?.let { client(it.ip) } ?: return null
        return try {
            DeviceReads.info(c)
        } catch (e: TapoException) {
            c.reset()
            null
        }
    }

    /** Moves known devices whose MAC turned up at a new address during a scan. */
    suspend fun updateAddresses(found: List<Found>) {
        val byMac = found.filter { !it.mac.isNullOrEmpty() }.associateBy { normMac(it.mac!!) }
        if (byMac.isEmpty()) return
        save { list ->
            list.map { d ->
                val f = if (d.mac.isNotEmpty()) byMac[normMac(d.mac)] else null
                when {
                    f == null -> d
                    // A camera keeps its confirmed certificate: at the new address only that one is trusted.
                    d.isCamera -> d.copy(ip = f.ip)
                    else -> d.copy(ip = f.ip, loginVersion = f.loginVersion ?: d.loginVersion)
                }
            }
        }
    }

    companion object {
        fun normMac(m: String) = m.uppercase().replace('-', ':')
        const val CAMERA_TYPE = "SMART.IPCAMERA"
    }
}

typealias Found = com.vknxworks.tapoctrl.tapo.Found
