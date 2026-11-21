package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Parsing for the features added in 0.4: components, children (power-strip sockets, hub sensors and switches),
 * the status light, auto-off and usage time. Field names as in python-kasa (kasa/smart/modules: childdevice,
 * led, autooff, temperaturesensor, humiditysensor, contactsensor, motionsensor) and the tapo crate
 * (responses/child_device_list_*, device_usage_result, default_plug_state). Parsing is lenient: missing or
 * odd fields stay null, and nothing here sends anything.
 */

private fun JsonObject.prim(k: String) = this[k] as? JsonPrimitive
private fun JsonObject.str(k: String) = prim(k)?.takeIf { it.isString }?.content
private fun JsonObject.int(k: String) = prim(k)?.intOrNull ?: prim(k)?.doubleOrNull?.toInt()
private fun JsonObject.dbl(k: String) = prim(k)?.doubleOrNull
private fun JsonObject.bool(k: String) = prim(k)?.booleanOrNull
private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

/** component_nego: `{"component_list":[{"id":"led","ver_code":1}, …]}`. */
object Components {
    fun parse(r: JsonElement): Set<String> =
        (r.obj()["component_list"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("id") }?.toSet().orEmpty()

    const val CHILD_DEVICE = "child_device"
    const val LED = "led"
    const val AUTO_OFF = "auto_off"
    const val DEVICE_USAGE = "device_usage"
}

/** What kind of child a hub or strip reports. */
enum class ChildKind { SOCKET, SWITCH, TEMPERATURE, CONTACT, MOTION, BUTTON, OTHER }

/** One child: a power-strip socket, or a sensor or switch paired with a hub. */
data class ChildInfo(
    val deviceId: String,
    val nickname: String,
    val model: String,
    val category: String,
    val position: Int?,
    val on: Boolean?,
    val brightness: Int?,
    val online: Boolean?,
    val lowBattery: Boolean?,
    val temperature: Double?,
    val fahrenheit: Boolean,
    val humidity: Int?,
    val open: Boolean?,
    val detected: Boolean?,
) {
    val kind: ChildKind
        get() = when {
            temperature != null || humidity != null -> ChildKind.TEMPERATURE
            open != null -> ChildKind.CONTACT
            detected != null -> ChildKind.MOTION
            category.contains("plug", true) -> ChildKind.SOCKET
            on != null && (category.contains("switch", true) || model.startsWith("S", true)) -> ChildKind.SWITCH
            on != null -> ChildKind.SOCKET
            model.startsWith("S200", true) || category.contains("button", true) -> ChildKind.BUTTON
            else -> ChildKind.OTHER
        }

    /** Whether it can be switched (a socket or a wall switch); sensors are read-only. */
    val switchable: Boolean get() = on != null && (kind == ChildKind.SOCKET || kind == ChildKind.SWITCH)

    class Page(val children: List<ChildInfo>, val sum: Int)

    companion object {
        fun parse(o: JsonObject): ChildInfo? {
            val id = o.str("device_id") ?: return null
            val unit = o.str("temp_unit") ?: o.str("temperature_unit")
            return ChildInfo(
                deviceId = id,
                nickname = o.str("nickname")?.let(DeviceInfo::b64).orEmpty(),
                model = o.str("model").orEmpty(),
                category = o.str("category").orEmpty(),
                position = o.int("position"),
                on = o.bool("device_on"),
                brightness = o.int("brightness"),
                online = o.str("status")?.let { it.equals("online", true) },
                lowBattery = o.bool("at_low_battery"),
                temperature = o.dbl("current_temp") ?: o.dbl("current_temperature"),
                fahrenheit = unit?.startsWith("f", true) == true,
                humidity = o.int("current_humidity"),
                open = o.bool("open"),
                detected = o.bool("detected"),
            )
        }

        /** One page of get_child_device_list: `{"child_device_list":[…], "start_index":0, "sum":3}`. */
        fun parsePage(r: JsonElement): Page {
            val o = r.obj()
            val list = (o["child_device_list"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parse) }.orEmpty()
            return Page(list.take(MAX_CHILDREN), (o.int("sum") ?: list.size).coerceIn(0, MAX_CHILDREN))
        }

        private const val MAX_CHILDREN = 64
    }
}

/** The status light: `led_rule` is "always", "never" or "night_mode". [raw] is sent back whole when it changes. */
data class LedInfo(val rule: String?, val raw: JsonObject) {
    val on: Boolean? get() = rule?.let { it != "never" }

    companion object {
        fun parse(r: JsonElement): LedInfo {
            val o = r.obj()
            return LedInfo(o.str("led_rule"), o)
        }
    }
}

/** Auto-off: whether it's on and after how many minutes. */
data class AutoOffConfig(val enabled: Boolean, val delayMin: Int) {
    companion object {
        fun parse(r: JsonElement): AutoOffConfig {
            val o = r.obj()
            return AutoOffConfig(o.bool("enable") == true, (o.int("delay_min") ?: 120).coerceIn(1, 24 * 60))
        }
    }
}

/** get_device_usage: minutes on today, in the last 7 and 30 days. */
data class DeviceUsage(val todayMin: Int?, val past7Min: Int?, val past30Min: Int?) {
    companion object {
        fun parse(r: JsonElement): DeviceUsage {
            val t = r.obj()["time_usage"]?.obj() ?: JsonObject(emptyMap())
            return DeviceUsage(t.int("today"), t.int("past7"), t.int("past30"))
        }
    }
}

/** What a plug does after a power cut: get_device_info's `default_states`. Read-only in TapoCtrl. */
enum class PowerCutState { LAST_STATE, ALWAYS_ON, ALWAYS_OFF }

fun parsePowerCutState(info: JsonElement): PowerCutState? {
    val d = info.obj()["default_states"]?.obj() ?: return null
    return when (d.str("type")) {
        "last_states" -> PowerCutState.LAST_STATE
        "custom" -> when (d["state"]?.obj()?.bool("on")) {
            true -> PowerCutState.ALWAYS_ON
            false -> PowerCutState.ALWAYS_OFF
            null -> null
        }
        else -> null
    }
}

/**
 * The reply to control_child: `{"responseData":{"error_code":0,"result":{…}}}` (python-kasa's
 * `_ChildProtocolWrapper`). A non-zero error code from the child is an error like any other.
 */
object ChildReply {
    fun check(r: JsonElement) {
        val data = r.obj()["responseData"]?.obj() ?: return
        val code = data.int("error_code") ?: 0
        if (code != 0) throw TapoException.DeviceError(code)
    }
}

/** One light preset saved on the device: brightness, and colour temperature or hue and saturation when it has them. */
data class LightPreset(val brightness: Int, val colorTemp: Int?, val hue: Int?, val saturation: Int?) {
    companion object {
        /**
         * get_preset_rules: `{"states":[{"brightness":50,"color_temp":2700,"hue":0,"saturation":0}, …]}`, or on
         * brightness-only bulbs `{"brightness":[10,30,50,…]}` (python-kasa's LightPreset module). States that hold an
         * effect instead of a brightness are skipped.
         */
        fun parse(r: JsonElement): List<LightPreset> {
            val o = r.obj()
            (o["states"] as? JsonArray)?.let { list ->
                return list.mapNotNull { e ->
                    val s = e as? JsonObject ?: return@mapNotNull null
                    val b = s.int("brightness") ?: return@mapNotNull null
                    LightPreset(b.coerceIn(1, 100), s.int("color_temp")?.takeIf { it > 0 }, s.int("hue"), s.int("saturation"))
                }.take(MAX)
            }
            return (o["brightness"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.coerceIn(1, 100) }
                ?.map { LightPreset(it, null, null, null) }?.take(MAX).orEmpty()
        }

        private const val MAX = 16
    }
}

/** A colour bulb's built-in effect (L1 "Party", L2 "Relax"), from get_dynamic_light_effect_rules. */
data class BulbEffect(val id: String, val name: String) {
    companion object {
        private val DEFAULT_NAMES = mapOf("L1" to "Party", "L2" to "Relax")

        fun parse(r: JsonElement): List<BulbEffect> =
            (r.obj()["rule_list"] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                // An edited name is base64, an unedited one empty (python-kasa's LightEffect module).
                val name = o.str("scene_name")?.takeIf { it.isNotBlank() }?.let(DeviceInfo::b64) ?: DEFAULT_NAMES[id] ?: id
                BulbEffect(id, name)
            }?.take(16).orEmpty()
    }
}

/** Which effect a light is showing, from get_device_info. */
data class EffectState(val enabled: Boolean, val name: String?, val id: String?)

/** A light strip's `lighting_effect`, or a colour bulb's `dynamic_light_effect_enable` and `_id`. */
fun parseEffectState(info: JsonElement): EffectState? {
    val o = info.obj()
    o["lighting_effect"]?.obj()?.let { e ->
        return EffectState((e.int("enable") ?: 0) != 0 || e.bool("enable") == true, e.str("name"), e.str("id"))
    }
    val dyn = o.bool("dynamic_light_effect_enable") ?: return null
    return EffectState(dyn, null, o.str("dynamic_light_effect_id"))
}
