package com.vknxworks.tapoctrl.data.settings

import com.vknxworks.tapoctrl.data.SavedDevice
import com.vknxworks.tapoctrl.net.LanAddress
import com.vknxworks.tapoctrl.tapo.SavedProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Settings > Export settings / Import settings: TapoCtrl's settings and your device list (address, name, model,
 * type, MAC and room) as a JSON file you keep. Never your Tapo account (its hashes stay encrypted on this phone)
 * and nothing of the app lock.
 *
 * An import never makes TapoCtrl less private: "Hide in screenshots" and "Hide in recent apps" can be turned on but
 * not off, and the lock timeout can get shorter but not longer. Devices are added to your list (matched by MAC
 * address, then by IP address); a device you already have only takes its room from the file. Only private network
 * addresses are taken.
 */
@Serializable
data class SettingsFileContent(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val appVersion: String? = null,
    val createdAt: String? = null,
    val grid: Boolean? = null,
    val tariff: String? = null,
    val currency: String? = null,
    val lockTimeout: String? = null,
    val hideScreenshots: Boolean? = null,
    val hideRecents: Boolean? = null,
    val devices: List<FileDevice>? = null,
) {
    companion object {
        const val FORMAT = "tapoctrl-settings"
        const val VERSION = 1
    }
}

@Serializable
data class FileDevice(
    val ip: String,
    val name: String = "",
    val model: String = "",
    val type: String = "",
    val mac: String = "",
    val room: String = "",
    /**
     * "klap" when the device was seen speaking KLAP. Only that value is imported: it makes the device stricter
     * (never switched to securePassthrough); a file can't mark a device as older firmware.
     */
    val protocol: String = "",
)

class SettingsFileException(val newer: Boolean) : Exception()

/** What an import changes in the device list: new devices, and rooms of devices you already have. */
class DeviceMerge(val list: List<SavedDevice>, val added: Int, val roomsChanged: Int)

object SettingsFile {
    const val MAX_BYTES = 512 * 1024
    private const val MAX_DEVICES = 200

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun export(s: AppSettings, devices: List<SavedDevice>, appVersion: String, now: String): String = json.encodeToString(
        SettingsFileContent.serializer(),
        SettingsFileContent(
            appVersion = appVersion,
            createdAt = now,
            grid = s.grid,
            tariff = s.tariff,
            currency = s.currency,
            lockTimeout = s.lockTimeout.name,
            hideScreenshots = s.hideScreenshots,
            hideRecents = s.hideRecents,
            devices = devices.map {
                FileDevice(it.ip, it.name, it.model, it.type, it.mac, it.room, protocol = if (it.protocol == SavedProtocol.KLAP) SavedProtocol.KLAP else "")
            },
        ),
    )

    fun parse(text: String): SettingsFileContent {
        val f = runCatching { json.decodeFromString(SettingsFileContent.serializer(), text) }.getOrNull()
            ?: throw SettingsFileException(newer = false)
        if (f.format != SettingsFileContent.FORMAT) throw SettingsFileException(newer = false)
        if (f.version > SettingsFileContent.VERSION) throw SettingsFileException(newer = true)
        if (f.version < 1 || (f.devices?.size ?: 0) > MAX_DEVICES) throw SettingsFileException(newer = false)
        return f
    }

    /** The settings after importing [f] over [current], with the privacy rules above. */
    fun applied(f: SettingsFileContent, current: AppSettings): AppSettings {
        val timeout = f.lockTimeout?.let { n -> LockTimeout.entries.firstOrNull { it.name == n } }
        return current.copy(
            grid = f.grid ?: current.grid,
            tariff = f.tariff?.take(12) ?: current.tariff,
            currency = f.currency?.take(6) ?: current.currency,
            lockTimeout = if (timeout != null && rank(timeout) <= rank(current.lockTimeout)) timeout else current.lockTimeout,
            hideScreenshots = current.hideScreenshots || f.hideScreenshots == true,
            hideRecents = current.hideRecents || f.hideRecents == true,
        )
    }

    private fun rank(t: LockTimeout) = if (t.millis < 0) Long.MAX_VALUE else t.millis

    private fun clean(s: String, max: Int) = s.filterNot { it.isISOControl() }.trim().take(max)

    private fun norm(mac: String) = mac.uppercase().replace('-', ':')

    /** The device list after importing [f]'s devices into [current]; [newId] makes ids for new devices. */
    fun merge(f: SettingsFileContent, current: List<SavedDevice>, newId: () -> String): DeviceMerge {
        val list = current.toMutableList()
        var added = 0
        var rooms = 0
        for (d in f.devices.orEmpty()) {
            val addr = LanAddress.parse(d.ip.trim()) ?: continue
            val ip = LanAddress.text(addr)
            val mac = clean(d.mac, 32)
            val room = clean(d.room, 40)
            val i = list.indexOfFirst { (mac.isNotEmpty() && it.mac.isNotEmpty() && norm(it.mac) == norm(mac)) || it.ip == ip }
            if (i >= 0) {
                if (room.isNotEmpty() && list[i].room != room) {
                    list[i] = list[i].copy(room = room)
                    rooms++
                }
            } else if (list.size < MAX_DEVICES) {
                list += SavedDevice(
                    newId(), ip, clean(d.name, 64), clean(d.model, 32), clean(d.type, 48), mac, room,
                    protocol = if (d.protocol == SavedProtocol.KLAP) SavedProtocol.KLAP else SavedProtocol.UNKNOWN,
                )
                added++
            }
        }
        return DeviceMerge(list, added, rooms)
    }
}
