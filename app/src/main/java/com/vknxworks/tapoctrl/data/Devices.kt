package com.vknxworks.tapoctrl.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.tapoctrl.net.LanAddress
import com.vknxworks.tapoctrl.tapo.DeviceInfo
import com.vknxworks.tapoctrl.tapo.DeviceReads
import com.vknxworks.tapoctrl.tapo.TapoMethods
import com.vknxworks.tapoctrl.tapo.DownloadState
import com.vknxworks.tapoctrl.tapo.EnergyUsage
import com.vknxworks.tapoctrl.tapo.FirmwareInfo
import com.vknxworks.tapoctrl.tapo.KlapClient
import com.vknxworks.tapoctrl.tapo.TapoException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

private val Context.deviceStore by preferencesDataStore(name = "devices")
private val DEVICES = stringPreferencesKey("devices_json")
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
)

/** What TapoCtrl currently knows about a device. */
data class DeviceState(
    val info: DeviceInfo? = null,
    val error: TapoException? = null,
    val busy: Boolean = false,
    val energy: EnergyUsage? = null,
    /** null: not asked yet; false: the device doesn't measure energy. */
    val energySupported: Boolean? = null,
    val updatedAt: Long = 0,
)

sealed interface AddResult {
    data class Added(val device: SavedDevice) : AddResult
    data object AlreadyAdded : AddResult
    data object NotLocal : AddResult
    data object NoAnswer : AddResult
}

class DeviceRepository(private val context: Context, scope: CoroutineScope, private val account: AccountVault) {

    val devices: StateFlow<List<SavedDevice>> = context.deviceStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> p[DEVICES]?.let { runCatching { json.decodeFromString<List<SavedDevice>>(it) }.getOrNull() } ?: emptyList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val states: StateFlow<Map<String, DeviceState>> = _states

    private val clients = HashMap<String, KlapClient>()

    private fun client(ip: String): KlapClient? {
        val addr = LanAddress.parse(ip) ?: return null
        synchronized(clients) {
            return clients.getOrPut(ip) { KlapClient(addr) { account.hashes() } }
        }
    }

    /** Forget every open session (after the account changes). */
    fun resetSessions() {
        synchronized(clients) { clients.values.forEach { it.reset() }; clients.clear() }
        _states.update { m -> m.mapValues { it.value.copy(error = null) } }
    }

    private suspend fun save(transform: (List<SavedDevice>) -> List<SavedDevice>) {
        try {
            context.deviceStore.edit { p ->
                val now = p[DEVICES]?.let { runCatching { json.decodeFromString<List<SavedDevice>>(it) }.getOrNull() } ?: emptyList()
                p[DEVICES] = json.encodeToString(transform(now))
            }
        } catch (e: IOException) {
        }
    }

    private fun setState(id: String, f: (DeviceState) -> DeviceState) {
        _states.update { it + (id to f(it[id] ?: DeviceState())) }
    }

    private suspend fun current(id: String): SavedDevice? =
        devices.value.firstOrNull { it.id == id } ?: devices.first().firstOrNull { it.id == id }

    /** Adds a device by address. It's added when it answers, even if your account doesn't fit yet. */
    suspend fun add(ip: String, hint: Found? = null): AddResult {
        val addr = LanAddress.parse(ip) ?: return AddResult.NotLocal
        val text = LanAddress.text(addr)
        devices.first().firstOrNull { it.ip == text }?.let { return AddResult.AlreadyAdded }
        val c = client(text)!!
        val info = try {
            DeviceReads.info(c)
        } catch (e: TapoException.Unreachable) {
            return AddResult.NoAnswer
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
        )
        save { it + d }
        setState(d.id) { DeviceState(info = info, updatedAt = if (info != null) System.currentTimeMillis() else 0) }
        if (info == null) refresh(d.id)
        return AddResult.Added(d)
    }

    suspend fun remove(id: String) {
        save { list -> list.filterNot { it.id == id } }
        _states.update { it - id }
    }

    suspend fun clearAll() {
        save { emptyList() }
        _states.value = emptyMap()
        resetSessions()
    }

    /** Asks every device for its state at once. */
    suspend fun refreshAll() = coroutineScope {
        devices.first().map { d -> async { refresh(d.id) } }.awaitAll()
    }

    suspend fun refresh(id: String) {
        val d = current(id) ?: return
        val c = client(d.ip) ?: return
        setState(id) { it.copy(busy = true) }
        try {
            val info = DeviceReads.info(c)
            setState(id) { it.copy(info = info, error = null, busy = false, updatedAt = System.currentTimeMillis()) }
            // Keep the saved name, model and MAC in step with the device.
            if (info.nickname != d.name || info.model != d.model || (info.mac ?: "") != d.mac || info.type != d.type) {
                save { list -> list.map { if (it.id == id) it.copy(name = info.nickname, model = info.model, type = info.type, mac = info.mac ?: it.mac) else it } }
            }
        } catch (e: TapoException) {
            setState(id) { it.copy(error = e, busy = false) }
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
                if (f != null && f.ip != d.ip) d.copy(ip = f.ip) else d
            }
        }
    }

    companion object {
        fun normMac(m: String) = m.uppercase().replace('-', ':')
    }
}

typealias Found = com.vknxworks.tapoctrl.tapo.Found
