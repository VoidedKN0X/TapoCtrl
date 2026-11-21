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
    /** Energy history (hourly, daily or monthly Wh) for the graphs. Read-only. */
    const val GET_ENERGY_DATA = "get_energy_data"
    const val GET_LATEST_FW = "get_latest_fw"
    const val GET_FW_DOWNLOAD_STATE = "get_fw_download_state"
    /** Which features a device has ("child_device", "led", "auto_off", …). Read-only. */
    const val COMPONENT_NEGO = "component_nego"
    /** Sockets of a power strip, or the sensors and switches paired with a hub. Read-only. */
    const val GET_CHILD_DEVICE_LIST = "get_child_device_list"
    /** The status light's rule (always, never, night mode). Read-only. */
    const val GET_LED_INFO = "get_led_info"
    /** Auto-off: on or off, and after how many minutes. Read-only. */
    const val GET_AUTO_OFF_CONFIG = "get_auto_off_config"
    /** How long the device was on today, in the last 7 and 30 days. Read-only. */
    const val GET_DEVICE_USAGE = "get_device_usage"
    /** The light presets saved on a bulb or strip. Read-only. */
    const val GET_PRESET_RULES = "get_preset_rules"
    /** A colour bulb's built-in effects (Party, Relax). Read-only. */
    const val GET_DYNAMIC_LIGHT_EFFECT_RULES = "get_dynamic_light_effect_rules"

    /** Change the device: only after a tap on that device's control. */
    const val SET_DEVICE_INFO = "set_device_info"
    /** Install firmware: only after the confirmation dialog. Never with parameters (no custom URL). */
    const val FW_DOWNLOAD = "fw_download"
    /** Status light rule: only from a tap on that switch. */
    const val SET_LED_INFO = "set_led_info"
    /** Auto-off: only from a tap on that setting. */
    const val SET_AUTO_OFF_CONFIG = "set_auto_off_config"
    /**
     * A request for one socket or child device, sent through its parent (python-kasa's `_ChildProtocolWrapper`).
     * Only set_device_info from a tap on that socket's switch is ever wrapped in it.
     */
    const val CONTROL_CHILD = "control_child"
    /** A light strip effect (python-kasa's built-in effect definitions): only from a tap on that effect. */
    const val SET_LIGHTING_EFFECT = "set_lighting_effect"
    /** A colour bulb's effect on or off: only from a tap on that effect. */
    const val SET_DYNAMIC_LIGHT_EFFECT_RULE_ENABLE = "set_dynamic_light_effect_rule_enable"

    val READS: Set<String> = setOf(
        GET_DEVICE_INFO, GET_ENERGY_USAGE, GET_CURRENT_POWER, GET_ENERGY_DATA, GET_LATEST_FW, GET_FW_DOWNLOAD_STATE,
        COMPONENT_NEGO, GET_CHILD_DEVICE_LIST, GET_LED_INFO, GET_AUTO_OFF_CONFIG, GET_DEVICE_USAGE,
        GET_PRESET_RULES, GET_DYNAMIC_LIGHT_EFFECT_RULES,
    )

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

    /** One energy graph (today, 7 days, 30 days, 12 months) from the device's own history. */
    suspend fun energyHistory(c: TapoCaller, range: EnergyRange, now: java.time.LocalDateTime): EnergySeries =
        EnergyHistory.read(c.readOnly(), range, now)

    /** The device's feature ids from component_nego; empty when it doesn't answer that. */
    suspend fun components(c: TapoCaller): Set<String> = Components.parse(c.readOnly().call(TapoMethods.COMPONENT_NEGO, null))

    /** Every child (socket, sensor, switch), page by page (the device sends up to 10 at a time). */
    suspend fun children(c: TapoCaller): List<ChildInfo> {
        val r = c.readOnly()
        val all = ArrayList<ChildInfo>()
        var start = 0
        repeat(MAX_CHILD_PAGES) {
            val page = ChildInfo.parsePage(r.call(TapoMethods.GET_CHILD_DEVICE_LIST, kotlinx.serialization.json.buildJsonObject { put("start_index", kotlinx.serialization.json.JsonPrimitive(start)) }))
            all += page.children
            start += page.children.size
            if (page.children.isEmpty() || start >= page.sum) return all
        }
        return all
    }

    private const val MAX_CHILD_PAGES = 10

    suspend fun led(c: TapoCaller): LedInfo = LedInfo.parse(c.readOnly().call(TapoMethods.GET_LED_INFO, null))

    suspend fun autoOff(c: TapoCaller): AutoOffConfig =
        AutoOffConfig.parse(c.readOnly().call(TapoMethods.GET_AUTO_OFF_CONFIG, kotlinx.serialization.json.buildJsonObject { put("start_index", kotlinx.serialization.json.JsonPrimitive(0)) }))

    suspend fun presets(c: TapoCaller): List<LightPreset> =
        LightPreset.parse(c.readOnly().call(TapoMethods.GET_PRESET_RULES, kotlinx.serialization.json.buildJsonObject { put("start_index", kotlinx.serialization.json.JsonPrimitive(0)) }))

    suspend fun bulbEffects(c: TapoCaller): List<BulbEffect> =
        BulbEffect.parse(c.readOnly().call(TapoMethods.GET_DYNAMIC_LIGHT_EFFECT_RULES, kotlinx.serialization.json.buildJsonObject { put("start_index", kotlinx.serialization.json.JsonPrimitive(0)) }))

    suspend fun usage(c: TapoCaller): DeviceUsage = DeviceUsage.parse(c.readOnly().call(TapoMethods.GET_DEVICE_USAGE, null))

    suspend fun latestFirmware(c: TapoCaller): FirmwareInfo = FirmwareInfo.parse(c.readOnly().call(TapoMethods.GET_LATEST_FW, null))

    suspend fun downloadState(c: TapoCaller): DownloadState =
        DownloadState.parse(c.readOnly().call(TapoMethods.GET_FW_DOWNLOAD_STATE, null))
}
