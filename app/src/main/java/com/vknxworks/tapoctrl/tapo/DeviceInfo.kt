package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** What get_device_info tells about a device. Parsing is lenient: missing fields stay null. */
data class DeviceInfo(
    val deviceId: String?,
    val model: String,
    val type: String,
    val nickname: String,
    val fwVersion: String?,
    val hwVersion: String?,
    val mac: String?,
    val ip: String?,
    val ssid: String?,
    val rssi: Int?,
    val signalLevel: Int?,
    val on: Boolean?,
    val brightness: Int?,
    val colorTemp: Int?,
    val colorTempRange: IntRange,
    val hue: Int?,
    val saturation: Int?,
    val overheated: Boolean,
    val onTimeSeconds: Long?,
) {
    val isLight: Boolean get() = type.contains("BULB", ignoreCase = true) || type.contains("LIGHT", ignoreCase = true)
    val isPlug: Boolean get() = type.contains("PLUG", ignoreCase = true)
    val hasBrightness: Boolean get() = brightness != null
    val hasColorTemp: Boolean get() = colorTemp != null && colorTempRange.first < colorTempRange.last
    val hasColor: Boolean get() = hue != null && saturation != null
    val kind: DeviceKind
        get() = when {
            isPlug -> DeviceKind.PLUG
            isLight && model.startsWith("L9", ignoreCase = true) -> DeviceKind.STRIP
            isLight -> DeviceKind.BULB
            else -> DeviceKind.OTHER
        }

    companion object {
        fun parse(r: JsonElement): DeviceInfo {
            val o = r as? JsonObject ?: JsonObject(emptyMap())
            val range = (o["color_temp_range"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            return DeviceInfo(
                deviceId = o.str("device_id"),
                model = o.str("model") ?: "",
                type = o.str("type") ?: o.str("device_type") ?: "",
                nickname = o.str("nickname")?.let(::b64) ?: "",
                fwVersion = o.str("fw_ver"),
                hwVersion = o.str("hw_ver"),
                mac = o.str("mac"),
                ip = o.str("ip"),
                ssid = o.str("ssid")?.let(::b64),
                rssi = o.int("rssi"),
                signalLevel = o.int("signal_level"),
                on = o.bool("device_on"),
                brightness = o.int("brightness"),
                colorTemp = o.int("color_temp"),
                colorTempRange = if (range != null && range.size == 2 && range[0] < range[1]) range[0]..range[1] else 2500..6500,
                hue = o.int("hue"),
                saturation = o.int("saturation"),
                overheated = o.bool("overheated") == true || o.str("overheat_status").let { it != null && it != "normal" },
                onTimeSeconds = (o["on_time"] as? JsonPrimitive)?.longOrNull,
            )
        }

        /** Tapo sends names and the Wi-Fi name as base64; plain text is kept as it is. */
        fun b64(s: String): String = try {
            String(Base64.getDecoder().decode(s), Charsets.UTF_8)
                .takeIf { d -> d.none { it == '�' || it < ' ' } } ?: s
        } catch (e: IllegalArgumentException) {
            s
        }

        fun toB64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    }
}

enum class DeviceKind { PLUG, BULB, STRIP, OTHER }

/** get_energy_usage: today's and this month's energy in Wh, and the power now in W. */
data class EnergyUsage(val todayWh: Double?, val monthWh: Double?, val powerW: Double?, val todayMinutes: Int?, val monthMinutes: Int?) {
    companion object {
        fun parse(r: JsonElement): EnergyUsage {
            val o = r as? JsonObject ?: JsonObject(emptyMap())
            return EnergyUsage(
                todayWh = o.dbl("today_energy"),
                monthWh = o.dbl("month_energy"),
                // Here current_power is in milliwatts; get_current_power reports watts.
                powerW = o.dbl("current_power")?.let { it / 1000.0 },
                todayMinutes = o.int("today_runtime"),
                monthMinutes = o.int("month_runtime"),
            )
        }

        fun parsePowerW(r: JsonElement): Double? = (r as? JsonObject)?.dbl("current_power")
    }
}

/** get_latest_fw. */
data class FirmwareInfo(val needUpgrade: Boolean, val version: String?, val releaseDate: String?, val notes: String?, val sizeKb: Long?) {
    companion object {
        fun parse(r: JsonElement): FirmwareInfo {
            val o = r as? JsonObject ?: JsonObject(emptyMap())
            return FirmwareInfo(
                needUpgrade = o.bool("need_to_upgrade") == true,
                version = o.str("fw_ver")?.takeIf { it.isNotBlank() },
                releaseDate = o.str("release_date")?.takeIf { it.isNotBlank() },
                notes = o.str("release_note")?.takeIf { it.isNotBlank() },
                sizeKb = (o["fw_size"] as? JsonPrimitive)?.longOrNull,
            )
        }
    }
}

/** get_fw_download_state. */
data class DownloadState(val status: Int?, val progress: Int?, val rebootSeconds: Int?, val upgradeSeconds: Int?) {
    companion object {
        fun parse(r: JsonElement): DownloadState {
            val o = r as? JsonObject ?: JsonObject(emptyMap())
            return DownloadState(o.int("status"), o.int("download_progress"), o.int("reboot_time"), o.int("upgrade_time"))
        }
    }
}

private fun JsonObject.prim(k: String) = this[k] as? JsonPrimitive
private fun JsonObject.str(k: String) = prim(k)?.takeIf { it.isString }?.content
private fun JsonObject.int(k: String) = prim(k)?.intOrNull ?: prim(k)?.doubleOrNull?.toInt()
private fun JsonObject.dbl(k: String) = prim(k)?.doubleOrNull
private fun JsonObject.bool(k: String) = prim(k)?.booleanOrNull
