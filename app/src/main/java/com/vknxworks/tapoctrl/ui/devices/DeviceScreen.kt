package com.vknxworks.tapoctrl.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.data.DeviceState
import com.vknxworks.tapoctrl.data.settings.AppSettings
import com.vknxworks.tapoctrl.tapo.DeviceInfo
import com.vknxworks.tapoctrl.tapo.TapoException
import com.vknxworks.tapoctrl.ui.components.ConfirmDialog
import com.vknxworks.tapoctrl.ui.components.CtrlHeroCard
import com.vknxworks.tapoctrl.ui.components.CtrlSquareButton
import com.vknxworks.tapoctrl.ui.components.Group
import com.vknxworks.tapoctrl.ui.components.GroupRow
import com.vknxworks.tapoctrl.ui.components.NameDialog
import com.vknxworks.tapoctrl.ui.components.ScreenTopBar
import com.vknxworks.tapoctrl.ui.components.StatRow
import com.vknxworks.tapoctrl.ui.components.StatTile
import com.vknxworks.tapoctrl.ui.components.WarningCard
import com.vknxworks.tapoctrl.ui.nav.AccountRoute
import com.vknxworks.tapoctrl.ui.nav.DeviceInfoRoute
import com.vknxworks.tapoctrl.ui.nav.EnergyRoute
import com.vknxworks.tapoctrl.ui.nav.FirmwareRoute
import com.vknxworks.tapoctrl.ui.nav.LocalNavigator
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import com.vknxworks.tapoctrl.ui.theme.CtrlType
import com.vknxworks.tapoctrl.ui.theme.Gutter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** One device: power, brightness and colour for lights, energy for plugs that measure it, and its actions. */
@Composable
fun DeviceScreen(id: String) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val devices by c.devices.devices.collectAsStateWithLifecycle()
    val states by c.devices.states.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == id }
    val state = states[id]
    val info = state?.info
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        c.devices.refresh(id)
        // Plugs: keep the power reading fresh while this page is open.
        while (isActive) {
            val s = c.devices.states.value[id]
            if (s?.info?.isPlug == true && s.energySupported != false) c.devices.refreshEnergy(id) else if (s?.info != null) break
            delay(15_000)
        }
    }

    if (device == null) {
        // Removed: nothing to show.
        LaunchedEffect(Unit) { nav.back() }
        return
    }

    fun send(params: kotlinx.serialization.json.JsonObject) {
        scope.launch { c.devices.set(id, params)?.let { toast(context, errorMessage(context, it)) } }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(device.label(state)) {
            CtrlSquareButton(Icons.Outlined.Refresh, stringResource(R.string.refresh), onClick = { scope.launch { c.devices.refresh(id) } }, enabled = state?.busy != true)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            state?.error?.let { e ->
                Column(Modifier.padding(horizontal = Gutter, vertical = 6.dp)) {
                    WarningCard(errorMessage(context, e), Icons.Outlined.ErrorOutline)
                }
                if (e is TapoException.WrongAccount || e is TapoException.NoAccount) {
                    Group {
                        GroupRow(stringResource(R.string.account_title), stringResource(R.string.account_open_sub), icon = Icons.Outlined.Key, showChevron = true, onClick = { nav.navigate(AccountRoute) })
                    }
                }
            }

            val on = info?.on
            CtrlHeroCard(
                label = info?.model ?: device.model.ifBlank { device.ip },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp),
                meta = device.ip,
                onClick = if (on != null && state.error == null) ({
                    scope.launch { c.devices.setOn(id, !on)?.let { toast(context, errorMessage(context, it)) } }
                }) else null,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            when (on) {
                                true -> R.string.power_on_big
                                false -> R.string.power_off_big
                                null -> R.string.power_unknown_big
                            },
                        ),
                        style = CtrlType.Hero,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    stringResource(
                        when (on) {
                            true -> R.string.tap_to_turn_off
                            false -> R.string.tap_to_turn_on
                            null -> if (state?.busy == true) R.string.connecting else R.string.no_state
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (info != null && info.isLight) LightControls(info, ::send)
            if (info?.isPlug == true && state.energySupported == true) {
                EnergyGroup(state, settings)
                // Graphs of the plug's own energy history (reads only).
                Group {
                    GroupRow(
                        stringResource(R.string.energy_history),
                        stringResource(R.string.energy_history_sub),
                        icon = Icons.Outlined.BarChart,
                        showChevron = true,
                        onClick = { nav.navigate(EnergyRoute(id)) },
                    )
                }
            }

            Group(stringResource(R.string.group_device)) {
                GroupRow(stringResource(R.string.rename), info?.nickname ?: device.name, icon = Icons.Outlined.DriveFileRenameOutline, showChevron = true, onClick = if (info != null) ({ renaming = true }) else null)
                GroupRow(stringResource(R.string.device_info), stringResource(R.string.device_info_sub), icon = Icons.Outlined.Info, showChevron = true, onClick = { nav.navigate(DeviceInfoRoute(id)) })
                GroupRow(stringResource(R.string.firmware_update), info?.fwVersion?.let { stringResource(R.string.firmware_current, it) }, icon = Icons.Outlined.SystemUpdate, showChevron = true, onClick = { nav.navigate(FirmwareRoute(id)) })
                GroupRow(
                    stringResource(R.string.remove_device),
                    stringResource(R.string.remove_device_sub),
                    icon = Icons.Outlined.DeleteOutline,
                    iconTint = CtrlColors.Error,
                    titleColor = CtrlColors.Error,
                    onClick = { removing = true },
                )
            }
        }
    }

    if (renaming) {
        NameDialog(stringResource(R.string.rename), info?.nickname ?: device.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch { c.devices.rename(id, name.take(32))?.let { toast(context, errorMessage(context, it)) } }
        }
    }
    if (removing) {
        ConfirmDialog(
            stringResource(R.string.remove_title),
            stringResource(R.string.remove_body),
            stringResource(R.string.remove_device),
            onDismiss = { removing = false },
        ) {
            removing = false
            scope.launch {
                c.devices.remove(id)
                nav.back()
            }
        }
    }
}

@Composable
private fun LightControls(info: DeviceInfo, send: (kotlinx.serialization.json.JsonObject) -> Unit) {
    if (!info.hasBrightness && !info.hasColorTemp && !info.hasColor) return
    Group(stringResource(R.string.group_light)) {
        if (info.hasBrightness) {
            ControlSlider(stringResource(R.string.brightness), info.brightness ?: 100, 1..100, display = { "$it %" }) { v ->
                send(buildJsonObject { put("brightness", v) })
            }
        }
        if (info.hasColorTemp) {
            val r = info.colorTempRange
            val temp = info.colorTemp ?: 0
            // Colour bulbs report color_temp 0 while showing a colour.
            val inColour = temp == 0
            val colourLabel = stringResource(R.string.colour_mode)
            ControlSlider(
                stringResource(R.string.colour_temperature),
                if (inColour) (r.first + r.last) / 2 else temp,
                r,
                step = 100,
                display = { if (inColour) colourLabel else "$it K" },
            ) { v -> send(buildJsonObject { put("color_temp", v) }) }
        }
        if (info.hasColor) {
            var hue by remember(info.hue) { mutableIntStateOf(info.hue ?: 0) }
            var sat by remember(info.saturation) { mutableIntStateOf(info.saturation ?: 100) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.colour), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(width = 44.dp, height = 24.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(Color.hsv(hue.toFloat().coerceIn(0f, 359f), (sat / 100f).coerceIn(0f, 1f), 1f))
                        .border(1.dp, CtrlColors.Outline, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                )
            }
            ControlSlider(stringResource(R.string.hue), hue, 0..360, display = { "$it°" }) { v ->
                hue = v
                send(buildJsonObject { put("hue", v); put("saturation", sat); put("color_temp", 0) })
            }
            ControlSlider(stringResource(R.string.saturation), sat, 1..100, display = { "$it %" }) { v ->
                sat = v
                send(buildJsonObject { put("hue", hue); put("saturation", v); put("color_temp", 0) })
            }
        }
    }
}

@Composable
private fun EnergyGroup(state: DeviceState, settings: AppSettings) {
    val e = state.energy ?: return
    val price = settings.tariffPerKwh
    fun kwh(wh: Double?) = wh?.let { String.format(Locale.getDefault(), "%.2f kWh", it / 1000.0) } ?: "–"
    fun cost(wh: Double?) = if (price != null && wh != null) String.format(Locale.getDefault(), "%.2f %s", wh / 1000.0 * price, settings.currency) else null
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp)) {
        com.vknxworks.tapoctrl.ui.components.SectionLabel(stringResource(R.string.group_energy), Modifier.padding(start = 4.dp, bottom = 8.dp))
        StatRow(
            { m -> StatTile(stringResource(R.string.power_now), e.powerW?.let { String.format(Locale.getDefault(), "%.1f W", it) } ?: "–", m) },
            { m -> StatTile(stringResource(R.string.energy_today), kwh(e.todayWh), m, sub = cost(e.todayWh)) },
        )
        Spacer(Modifier.size(12.dp))
        StatRow(
            { m -> StatTile(stringResource(R.string.energy_month), kwh(e.monthWh), m, sub = cost(e.monthWh)) },
            { m ->
                StatTile(
                    stringResource(R.string.runtime_today),
                    e.todayMinutes?.let { String.format(Locale.getDefault(), "%d:%02d h", it / 60, it % 60) } ?: "–",
                    m,
                )
            },
        )
        Text(
            stringResource(if (price == null) R.string.energy_footer_no_tariff else R.string.energy_footer),
            style = MaterialTheme.typography.bodySmall,
            color = CtrlColors.Muted,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )

    }
}
