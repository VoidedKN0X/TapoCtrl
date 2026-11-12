package com.vknxworks.tapoctrl.ui.devices

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.data.DeviceState
import com.vknxworks.tapoctrl.data.SavedDevice
import com.vknxworks.tapoctrl.tapo.DeviceKind
import com.vknxworks.tapoctrl.tapo.TapoException
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import com.vknxworks.tapoctrl.ui.theme.CtrlShapes
import com.vknxworks.tapoctrl.ui.theme.CtrlType

/** A sentence for the user about what went wrong. */
fun errorMessage(context: Context, e: TapoException): String = when (e) {
    is TapoException.NotLocal -> context.getString(R.string.err_not_local)
    is TapoException.Unreachable -> context.getString(R.string.err_unreachable)
    is TapoException.WrongAccount -> context.getString(R.string.err_wrong_account)
    is TapoException.NoAccount -> context.getString(R.string.err_no_account)
    is TapoException.LegacyFirmware -> context.getString(R.string.err_legacy)
    is TapoException.DeviceError -> context.getString(R.string.err_device, e.code)
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** The device's name: its own nickname, else the model, else its address. */
fun SavedDevice.label(state: DeviceState?): String =
    state?.info?.nickname?.takeIf { it.isNotBlank() } ?: name.takeIf { it.isNotBlank() } ?: model.takeIf { it.isNotBlank() } ?: ip

fun SavedDevice.kind(state: DeviceState?): DeviceKind = state?.info?.kind ?: when {
    type.contains("PLUG", true) -> DeviceKind.PLUG
    type.contains("BULB", true) && model.startsWith("L9", true) -> DeviceKind.STRIP
    type.contains("BULB", true) -> DeviceKind.BULB
    else -> DeviceKind.OTHER
}

fun kindIcon(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.PLUG -> Icons.Outlined.Power
    DeviceKind.BULB -> Icons.Outlined.Lightbulb
    DeviceKind.STRIP -> Icons.Outlined.LightMode
    DeviceKind.OTHER -> Icons.Outlined.DevicesOther
}

/** Short status for a tile: ON, OFF, or why it can't be reached. */
@Composable
fun statusText(state: DeviceState?): Pair<String, Boolean> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val on = state?.info?.on
    return when {
        state?.error is TapoException.LegacyFirmware -> ctx.getString(R.string.status_old_firmware) to false
        state?.error is TapoException.WrongAccount || state?.error is TapoException.NoAccount -> ctx.getString(R.string.status_sign_in) to false
        state?.error != null -> ctx.getString(R.string.status_offline) to false
        on == true -> ctx.getString(R.string.status_on) to true
        on == false -> ctx.getString(R.string.status_off) to false
        else -> ctx.getString(R.string.status_unknown) to false
    }
}

/** The big square power button: accent while on. */
@Composable
fun PowerButton(on: Boolean?, enabled: Boolean, onClick: () -> Unit, size: Dp = 56.dp) {
    val desc = androidx.compose.ui.res.stringResource(R.string.power)
    val state = androidx.compose.ui.res.stringResource(if (on == true) R.string.status_on else R.string.status_off)
    Box(
        Modifier
            .size(size)
            .semantics { contentDescription = desc; stateDescription = state }
            .clip(CtrlShapes.Control)
            .background(if (on == true) CtrlColors.Accent else CtrlColors.Raised)
            .border(1.dp, if (on == true) CtrlColors.Accent else CtrlColors.ControlOutline, CtrlShapes.Control)
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.PowerSettingsNew,
            null,
            Modifier.size(size * 0.45f),
            tint = when {
                !enabled -> CtrlColors.Inactive
                on == true -> CtrlColors.OnAccent
                else -> CtrlColors.Text
            },
        )
    }
}

/**
 * A labelled slider that sends its value when you let go. [display] formats the value next to the label.
 */
@Composable
fun ControlSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    enabled: Boolean = true,
    display: (Int) -> String = { it.toString() },
    onCommit: (Int) -> Unit,
) {
    var v by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { v = value.coerceIn(range).toFloat() }
    val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = CtrlColors.Text, modifier = Modifier.weight(1f))
            Text(display(v.toInt()), style = CtrlType.Data, color = CtrlColors.Muted)
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onCommit(v.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (steps in 1..200) steps else 0,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = CtrlColors.Accent,
                activeTrackColor = CtrlColors.Accent,
                inactiveTrackColor = CtrlColors.ProgressTrack,
                activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
                inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
    }
}
