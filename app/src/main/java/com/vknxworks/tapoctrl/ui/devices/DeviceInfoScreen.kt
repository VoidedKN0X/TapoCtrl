package com.vknxworks.tapoctrl.ui.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.ui.components.Group
import com.vknxworks.tapoctrl.ui.components.GroupRow
import com.vknxworks.tapoctrl.ui.components.ScreenTopBar

/** Everything get_device_info tells about the device's hardware and connection. */
@Composable
fun DeviceInfoScreen(id: String) {
    val c = LocalContext.current.container
    val devices by c.devices.devices.collectAsStateWithLifecycle()
    val states by c.devices.states.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == id } ?: return
    val state = states[id]
    val info = state?.info
    LaunchedEffect(id) { c.devices.refresh(id) }
    val unknown = stringResource(R.string.unknown)

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.device_info))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Group(stringResource(R.string.group_device)) {
                GroupRow(stringResource(R.string.info_name), device.label(state))
                GroupRow(stringResource(R.string.info_model), info?.model ?: device.model.ifBlank { unknown })
                GroupRow(stringResource(R.string.info_type), info?.type ?: device.type.ifBlank { unknown })
                GroupRow(stringResource(R.string.info_firmware), info?.fwVersion ?: unknown)
                GroupRow(stringResource(R.string.info_hardware), info?.hwVersion ?: unknown)
                if (info?.overheated == true) GroupRow(stringResource(R.string.info_overheated), stringResource(R.string.info_overheated_sub))
            }
            Group(stringResource(R.string.group_network)) {
                GroupRow(stringResource(R.string.info_ip), device.ip)
                GroupRow(stringResource(R.string.info_mac), info?.mac ?: device.mac.ifBlank { unknown })
                GroupRow(stringResource(R.string.info_wifi), info?.ssid ?: unknown)
                GroupRow(
                    stringResource(R.string.info_signal),
                    when {
                        info?.rssi != null && info.signalLevel != null -> stringResource(R.string.info_signal_value, info.rssi, info.signalLevel)
                        info?.rssi != null -> "${info.rssi} dBm"
                        else -> unknown
                    },
                )
                GroupRow(
                    stringResource(R.string.info_protocol),
                    state?.error?.let { errorMessage(LocalContext.current, it) } ?: stringResource(R.string.info_protocol_klap),
                )
            }
        }
    }
}
