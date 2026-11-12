package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Something that sends one Tapo method and returns its result: [KlapClient], or a fake in tests. */
fun interface TapoCaller {
    suspend fun call(method: String, params: JsonObject?): JsonElement
}

/**
 * The one list of methods TapoCtrl may send without you tapping a control. Everything TapoCtrl does on its own
 * (app start, resume, pull to refresh, adding a device, the device pages, energy, firmware check, re-login)
 * goes through [readOnly], which refuses anything else. Methods that change a device (set_device_info,
 * fw_download) are sent only from the explicit user actions in [com.vknxworks.tapoctrl.data.DeviceRepository].
 */
object TapoMethods {
    const val GET_DEVICE_INFO = "get_device_info"
    const val GET_ENERGY_USAGE = "get_energy_usage"
    const val GET_CURRENT_POWER = "get_current_power"
    const val GET_LATEST_FW = "get_latest_fw"
    const val GET_FW_DOWNLOAD_STATE = "get_fw_download_state"

    /** Change the device: only after a tap on that device's control. */
    const val SET_DEVICE_INFO = "set_device_info"
    /** Install firmware: only after the confirmation dialog. Never with parameters (no custom URL). */
    const val FW_DOWNLOAD = "fw_download"

    val READS: Set<String> = setOf(GET_DEVICE_INFO, GET_ENERGY_USAGE, GET_CURRENT_POWER, GET_LATEST_FW, GET_FW_DOWNLOAD_STATE)

    fun isRead(method: String): Boolean = method in READS
}

/** Thrown when a code path that must only read tries to send anything else. A bug, never shown as normal. */
class WriteRefused(method: String) : IllegalStateException("Refused to send $method from a read-only path")

/** A view of this caller that can only send [TapoMethods.READS]. */
fun TapoCaller.readOnly(): TapoCaller {
    val inner = this
    return TapoCaller { method, params ->
        if (!TapoMethods.isRead(method)) throw WriteRefused(method)
        inner.call(method, params)
    }
}

/** The read paths: what TapoCtrl asks a device without being told to change anything. */
object DeviceReads {
    suspend fun info(c: TapoCaller): DeviceInfo = DeviceInfo.parse(c.readOnly().call(TapoMethods.GET_DEVICE_INFO, null))

    suspend fun energy(c: TapoCaller): EnergyUsage {
        val r = c.readOnly()
        var usage = EnergyUsage.parse(r.call(TapoMethods.GET_ENERGY_USAGE, null))
        val now = try {
            EnergyUsage.parsePowerW(r.call(TapoMethods.GET_CURRENT_POWER, null))
        } catch (e: TapoException) {
            null
        }
        if (now != null) usage = usage.copy(powerW = now)
        return usage
    }

    suspend fun latestFirmware(c: TapoCaller): FirmwareInfo = FirmwareInfo.parse(c.readOnly().call(TapoMethods.GET_LATEST_FW, null))

    suspend fun downloadState(c: TapoCaller): DownloadState =
        DownloadState.parse(c.readOnly().call(TapoMethods.GET_FW_DOWNLOAD_STATE, null))
}
