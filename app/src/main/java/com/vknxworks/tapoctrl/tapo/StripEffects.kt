package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The 17 built-in light strip effects (Aurora, Ocean, Rainbow, Sunset, …), exactly as python-kasa sends them with
 * set_lighting_effect (kasa/smart/effects.py, GPL-3.0, converted unchanged to `assets/strip_effects.json`).
 */
object StripEffects {
    fun parse(text: String): List<JsonObject> =
        (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { (it["name"] as? JsonPrimitive)?.isString == true }
            .orEmpty()

    fun name(effect: JsonObject): String = (effect["name"] as? JsonPrimitive)?.content.orEmpty()

    /** [effect] switched on with [brightness] (1-100), or off: the same definition with enable 0. */
    fun request(effect: JsonObject, on: Boolean, brightness: Int?): JsonObject {
        val m = effect.toMutableMap()
        m["enable"] = JsonPrimitive(if (on) 1 else 0)
        if (brightness != null) m["brightness"] = JsonPrimitive(brightness.coerceIn(1, 100))
        return JsonObject(m)
    }

    const val ASSET = "strip_effects.json"
    const val COMPONENT = "light_strip_lighting_effect"
    const val BULB_COMPONENT = "light_effect"
    const val PRESET_COMPONENT = "preset"
}
