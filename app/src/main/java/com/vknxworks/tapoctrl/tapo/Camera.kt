package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A position saved on a camera that pans and tilts. */
data class CameraPreset(val id: String, val name: String)

/** What TapoCtrl knows about a camera. Parsing is lenient: missing fields stay null. */
data class CameraStatus(
    val model: String,
    val name: String,
    val type: String,
    val fwVersion: String?,
    val hwVersion: String?,
    val mac: String?,
    /** Privacy mode (the lens mask): true = on, the camera doesn't record or stream. Null when unknown. */
    val privacy: Boolean? = null,
    val components: Set<String> = emptySet(),
    val presets: List<CameraPreset> = emptyList(),
) {
    val canPanTilt: Boolean get() = Camera.PTZ in components
}

/**
 * The camera requests TapoCtrl sends and how it reads the replies. Every request and field name is as in
 * python-kasa (smartcam/modules: device, lensmask, pantilt), pytapo (getBasicInfo, get/setPrivacyMode, moveMotor,
 * getPresets/setPreset, reboot) and the tapo crate (requests/smart_cam). Nothing here sends anything.
 */
object Camera {
    const val PTZ = "ptz"
    const val LENS_MASK = "lensMask"

    /** Pan and tilt steps per tap: python-kasa's defaults (DEFAULT_PAN_STEP 30, DEFAULT_TILT_STEP 10). */
    const val PAN_STEP = 30
    const val TILT_STEP = 10

    fun deviceInfoParams(): JsonObject = buildJsonObject { putJsonObject("device_info") { putJsonArray("name") { add("basic_info") } } }
    fun lensMaskParams(): JsonObject = buildJsonObject { putJsonObject("lens_mask") { putJsonArray("name") { add("lens_mask_info") } } }
    fun componentsParams(): JsonObject = buildJsonObject { putJsonObject("app_component") { put("name", "app_component_list") } }
    fun presetsParams(): JsonObject = buildJsonObject { putJsonObject("preset") { putJsonArray("name") { add("preset") } } }

    fun setLensMaskParams(on: Boolean): JsonObject =
        buildJsonObject { putJsonObject("lens_mask") { putJsonObject("lens_mask_info") { put("enabled", if (on) "on" else "off") } } }

    fun moveParams(x: Int, y: Int): JsonObject =
        buildJsonObject { putJsonObject("motor") { putJsonObject("move") { put("x_coord", x.toString()); put("y_coord", y.toString()) } } }

    fun gotoPresetParams(id: String): JsonObject =
        buildJsonObject { putJsonObject("preset") { putJsonObject("goto_preset") { put("id", id) } } }

    /** pytapo's reboot for cameras: {"system":{"reboot":"null"}}. */
    fun rebootParams(): JsonObject = buildJsonObject { putJsonObject("system") { put("reboot", "null") } }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.content

    fun parseInfo(r: JsonElement): CameraStatus {
        val b = r.obj()?.get("device_info").obj()?.get("basic_info").obj() ?: JsonObject(emptyMap())
        return CameraStatus(
            model = b.s("device_model").orEmpty(),
            name = b.s("device_alias") ?: b.s("device_name").orEmpty(),
            type = b.s("device_type") ?: "SMART.IPCAMERA",
            fwVersion = b.s("sw_version"),
            hwVersion = b.s("hw_version"),
            mac = b.s("mac"),
        )
    }

    fun parseLensMask(r: JsonElement): Boolean? =
        when (r.obj()?.get("lens_mask").obj()?.get("lens_mask_info").obj()?.s("enabled")) {
            "on" -> true
            "off" -> false
            else -> null
        }

    fun parseComponents(r: JsonElement): Set<String> =
        (r.obj()?.get("app_component").obj()?.get("app_component_list") as? JsonArray)
            ?.mapNotNull { it.obj()?.s("name") }?.toSet().orEmpty()

    /** {"preset":{"preset":{"id":["1",…],"name":["Door",…]}}}: ids and names side by side. */
    fun parsePresets(r: JsonElement): List<CameraPreset> {
        val p = r.obj()?.get("preset").obj()?.get("preset").obj() ?: return emptyList()
        val ids = (p["id"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val names = (p["name"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        return ids.mapIndexed { i, id -> CameraPreset(id, names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: id) }.take(64)
    }
}
