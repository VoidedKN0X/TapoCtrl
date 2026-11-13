package com.vknxworks.tapoctrl.ui.nav

import kotlinx.serialization.Serializable

/** Your devices, each with its on/off button. TapoCtrl opens straight into it. */
@Serializable data object HomeRoute

/** Add a device by address, or scan the network. */
@Serializable data object AddRoute

/** Your Tapo account: email and password once, kept only as hashes. */
@Serializable data object AccountRoute

/** One device: power, light controls, energy and its actions. */
@Serializable data class DeviceRoute(val id: String)

/** Energy use graphs (today, 7 days, 30 days, 12 months) for a plug that measures energy. Read-only. */
@Serializable data class EnergyRoute(val id: String)

/** Model, firmware, hardware, MAC, signal and address. */
@Serializable data class DeviceInfoRoute(val id: String)

/** Check for and install the device's own firmware update. */
@Serializable data class FirmwareRoute(val id: String)

// Opened from the profile picture: the You page, and Settings from there
@Serializable data object YouRoute
@Serializable data object SettingsRoute
