package com.vknxworks.tapoctrl.ui.devices

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.tapo.Camera
import com.vknxworks.tapoctrl.tapo.TapoException
import com.vknxworks.tapoctrl.ui.components.ConfirmDialog
import com.vknxworks.tapoctrl.ui.components.CtrlHeroCard
import com.vknxworks.tapoctrl.ui.components.CtrlSquareButton
import com.vknxworks.tapoctrl.ui.components.Group
import com.vknxworks.tapoctrl.ui.components.GroupRow
import com.vknxworks.tapoctrl.ui.components.ScreenTopBar
import com.vknxworks.tapoctrl.ui.components.WarningCard
import com.vknxworks.tapoctrl.ui.nav.AccountRoute
import com.vknxworks.tapoctrl.ui.nav.LocalNavigator
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import com.vknxworks.tapoctrl.ui.theme.CtrlType
import com.vknxworks.tapoctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/**
 * One camera: privacy mode, pan and tilt with the camera's saved positions (cameras that have them), its details
 * and restart. Opening and refreshing only read; each command is sent from a tap and never sent twice.
 * No live video.
 */
@Composable
fun CameraScreen(id: String) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val devices by c.devices.devices.collectAsStateWithLifecycle()
    val states by c.devices.states.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == id }
    val state = states[id]
    val cam = state?.camera
    var removing by remember { mutableStateOf(false) }
    var rebooting by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(id) { c.devices.refresh(id) }

    if (device == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
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
                if (e is TapoException.WrongAccount || e is TapoException.NoAccount || e is TapoException.AccountIncomplete) {
                    Group {
                        GroupRow(stringResource(R.string.account_title), stringResource(R.string.account_open_sub), icon = Icons.Outlined.Key, showChevron = true, onClick = { nav.navigate(AccountRoute) })
                    }
                }
            }

            val privacy = cam?.privacy
            CtrlHeroCard(
                label = stringResource(R.string.camera_privacy),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp),
                meta = cam?.model?.ifBlank { null } ?: device.model.ifBlank { device.ip },
                onClick = if (privacy != null && state?.error == null && !sending) ({
                    sending = true
                    scope.launch {
                        c.devices.setPrivacy(id, !privacy)?.let { toast(context, errorMessage(context, it)) }
                        sending = false
                    }
                }) else null,
            ) {
                Text(
                    stringResource(
                        when (privacy) {
                            true -> R.string.camera_privacy_on_big
                            false -> R.string.camera_privacy_off_big
                            null -> R.string.power_unknown_big
                        },
                    ),
                    style = CtrlType.Hero,
                )
                Text(
                    stringResource(
                        when (privacy) {
                            true -> R.string.camera_privacy_on_sub
                            false -> R.string.camera_privacy_off_sub
                            null -> if (state?.busy == true) R.string.connecting else R.string.no_state
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (cam?.canPanTilt == true) {
                Group(stringResource(R.string.camera_pan_tilt), footer = stringResource(R.string.camera_pan_tilt_footer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CtrlSquareButton(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.camera_tilt_up), onClick = {
                            scope.launch { c.devices.moveCamera(id, 0, Camera.TILT_STEP)?.let { toast(context, errorMessage(context, it)) } }
                        }, size = 56.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CtrlSquareButton(Icons.Outlined.KeyboardArrowLeft, stringResource(R.string.camera_pan_left), onClick = {
                                scope.launch { c.devices.moveCamera(id, -Camera.PAN_STEP, 0)?.let { toast(context, errorMessage(context, it)) } }
                            }, size = 56.dp)
                            Spacer(Modifier.size(56.dp))
                            CtrlSquareButton(Icons.Outlined.KeyboardArrowRight, stringResource(R.string.camera_pan_right), onClick = {
                                scope.launch { c.devices.moveCamera(id, Camera.PAN_STEP, 0)?.let { toast(context, errorMessage(context, it)) } }
                            }, size = 56.dp)
                        }
                        CtrlSquareButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.camera_tilt_down), onClick = {
                            scope.launch { c.devices.moveCamera(id, 0, -Camera.TILT_STEP)?.let { toast(context, errorMessage(context, it)) } }
                        }, size = 56.dp)
                    }
                }
                Group(stringResource(R.string.camera_positions), footer = if (cam.presets.isEmpty()) stringResource(R.string.camera_positions_none) else null) {
                    cam.presets.forEach { p ->
                        GroupRow(p.name, icon = Icons.Outlined.Place, onClick = {
                            scope.launch { c.devices.gotoCameraPreset(id, p.id)?.let { toast(context, errorMessage(context, it)) } }
                        })
                    }
                }
            }

            Group(stringResource(R.string.group_device)) {
                GroupRow(stringResource(R.string.info_model), value = cam?.model?.ifBlank { null } ?: device.model.ifBlank { "–" })
                GroupRow(stringResource(R.string.info_firmware), value = cam?.fwVersion?.substringBefore(' ') ?: "–")
                GroupRow(stringResource(R.string.info_hardware), value = cam?.hwVersion ?: "–")
                GroupRow(stringResource(R.string.info_mac), value = (cam?.mac ?: device.mac).ifBlank { "–" })
                GroupRow(stringResource(R.string.info_ip), value = device.ip)
                GroupRow(stringResource(R.string.camera_certificate), device.certSha256.ifBlank { stringResource(R.string.camera_certificate_none) })
                GroupRow(
                    stringResource(R.string.camera_restart),
                    stringResource(R.string.camera_restart_sub),
                    icon = Icons.Outlined.RestartAlt,
                    onClick = if (state?.error == null && cam != null) ({ rebooting = true }) else null,
                )
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

    if (rebooting) {
        ConfirmDialog(
            stringResource(R.string.camera_restart_title),
            stringResource(R.string.camera_restart_body),
            stringResource(R.string.camera_restart),
            onDismiss = { rebooting = false },
        ) {
            rebooting = false
            scope.launch {
                val e = c.devices.rebootCamera(id)
                toast(context, if (e != null) errorMessage(context, e) else context.getString(R.string.camera_restarting))
            }
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
