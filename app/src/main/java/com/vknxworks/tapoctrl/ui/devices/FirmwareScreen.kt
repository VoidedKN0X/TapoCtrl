package com.vknxworks.tapoctrl.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.tapo.FirmwareInfo
import com.vknxworks.tapoctrl.tapo.TapoException
import com.vknxworks.tapoctrl.ui.components.ConfirmDialog
import com.vknxworks.tapoctrl.ui.components.CtrlButton
import com.vknxworks.tapoctrl.ui.components.CtrlCard
import com.vknxworks.tapoctrl.ui.components.CtrlProgressBar
import com.vknxworks.tapoctrl.ui.components.Group
import com.vknxworks.tapoctrl.ui.components.GroupRow
import com.vknxworks.tapoctrl.ui.components.ScreenTopBar
import com.vknxworks.tapoctrl.ui.components.SectionLabel
import com.vknxworks.tapoctrl.ui.components.WarningCard
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import com.vknxworks.tapoctrl.ui.theme.CtrlType
import com.vknxworks.tapoctrl.ui.theme.Gutter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Fw {
    data object Idle : Fw
    data object Checking : Fw
    data class UpToDate(val version: String?) : Fw
    data class Available(val fw: FirmwareInfo) : Fw
    /** [phase]: downloading, installing or restarting. */
    data class Installing(val phase: Int, val progress: Int?) : Fw
    data class Done(val version: String?, val changed: Boolean) : Fw
    data class Failed(val message: String) : Fw
}

/**
 * Firmware update, done by the device itself: get_latest_fw asks the device whether TP-Link has a newer version
 * for it; fw_download (no parameters) tells it to fetch and install that signed firmware. TapoCtrl never passes a
 * download address and never handles the firmware file. Progress comes from get_fw_download_state, then the page
 * waits for the device to restart and answer again.
 */
@Composable
fun FirmwareScreen(id: String) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val states by c.devices.states.collectAsStateWithLifecycle()
    val current = states[id]?.info?.fwVersion
    var fw by remember { mutableStateOf<Fw>(Fw.Idle) }
    var confirm by remember { mutableStateOf(false) }

    // Keep the screen on while the update runs, so you can watch it finish.
    val view = LocalView.current
    val installing = fw is Fw.Installing
    DisposableEffect(installing) {
        view.keepScreenOn = installing
        onDispose { view.keepScreenOn = false }
    }

    fun check() {
        fw = Fw.Checking
        scope.launch {
            fw = try {
                val f = c.devices.latestFirmware(id)
                if (f.needUpgrade) Fw.Available(f) else Fw.UpToDate(current)
            } catch (e: TapoException) {
                Fw.Failed(errorMessage(context, e))
            }
        }
    }

    fun install() {
        val before = current
        fw = Fw.Installing(PHASE_DOWNLOAD, 0)
        scope.launch {
            try {
                c.devices.startFirmwareDownload(id)
            } catch (e: TapoException) {
                fw = Fw.Failed(errorMessage(context, e))
                return@launch
            }
            var wentAway = false
            val deadline = System.currentTimeMillis() + 15 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(2_000)
                if (!wentAway) {
                    try {
                        val s = c.devices.downloadState(id)
                        val p = s.progress
                        fw = Fw.Installing(if (p != null && p >= 100) PHASE_INSTALL else PHASE_DOWNLOAD, p)
                        continue
                    } catch (e: TapoException.DeviceError) {
                        fw = Fw.Installing(PHASE_INSTALL, null)
                        continue
                    } catch (e: TapoException) {
                        // No answer: it's flashing or restarting.
                        wentAway = true
                        fw = Fw.Installing(PHASE_RESTART, null)
                    }
                }
                val info = c.devices.probeInfo(id)
                if (info != null) {
                    c.devices.refresh(id)
                    fw = Fw.Done(info.fwVersion, info.fwVersion != before)
                    return@launch
                }
            }
            fw = Fw.Failed(context.getString(R.string.fw_timeout))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.firmware_update))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Group(footer = stringResource(R.string.fw_footer)) {
                GroupRow(stringResource(R.string.fw_installed), current ?: stringResource(R.string.unknown), icon = Icons.Outlined.SystemUpdate)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = fw) {
                    Fw.Idle -> CtrlButton(stringResource(R.string.fw_check), onClick = ::check, icon = Icons.Outlined.Search, modifier = Modifier.fillMaxWidth())
                    Fw.Checking -> CtrlButton(stringResource(R.string.fw_checking), onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
                    is Fw.UpToDate -> {
                        WarningLikeCard(stringResource(R.string.fw_up_to_date), Icons.Outlined.CheckCircle)
                        CtrlButton(stringResource(R.string.fw_check_again), onClick = ::check, icon = Icons.Outlined.Search, modifier = Modifier.fillMaxWidth())
                    }
                    is Fw.Available -> {
                        CtrlCard(Modifier.fillMaxWidth()) {
                            SectionLabel(stringResource(R.string.fw_new_version))
                            Text(s.fw.version ?: stringResource(R.string.unknown), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
                            s.fw.releaseDate?.let { Text(it, style = CtrlType.Data, color = CtrlColors.Muted) }
                            Text(
                                s.fw.notes ?: stringResource(R.string.fw_no_notes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = CtrlColors.Text,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        CtrlButton(stringResource(R.string.fw_install), onClick = { confirm = true }, icon = Icons.Outlined.SystemUpdate, modifier = Modifier.fillMaxWidth())
                    }
                    is Fw.Installing -> {
                        CtrlCard(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(
                                    when (s.phase) {
                                        PHASE_DOWNLOAD -> R.string.fw_downloading
                                        PHASE_INSTALL -> R.string.fw_installing
                                        else -> R.string.fw_restarting
                                    },
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (s.progress != null && s.phase == PHASE_DOWNLOAD) {
                                Text("${s.progress} %", style = CtrlType.Data, color = CtrlColors.Muted, modifier = Modifier.padding(vertical = 6.dp))
                                CtrlProgressBar(s.progress / 100f, color = CtrlColors.Accent)
                            } else {
                                androidx.compose.material3.LinearProgressIndicator(
                                    Modifier.fillMaxWidth().padding(top = 10.dp),
                                    color = CtrlColors.Accent,
                                    trackColor = CtrlColors.ProgressTrack,
                                )
                            }
                        }
                        Text(stringResource(R.string.fw_dont_unplug), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
                    }
                    is Fw.Done -> WarningLikeCard(
                        if (s.changed) stringResource(R.string.fw_done, s.version ?: "") else stringResource(R.string.fw_done_same, s.version ?: ""),
                        Icons.Outlined.CheckCircle,
                    )
                    is Fw.Failed -> {
                        WarningCard(s.message, Icons.Outlined.ErrorOutline)
                        CtrlButton(stringResource(R.string.fw_check_again), onClick = ::check, icon = Icons.Outlined.Search, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (confirm) {
        val v = (fw as? Fw.Available)?.fw?.version ?: ""
        ConfirmDialog(
            stringResource(R.string.fw_confirm_title),
            stringResource(R.string.fw_confirm_body, v),
            stringResource(R.string.fw_install),
            onDismiss = { confirm = false },
        ) {
            confirm = false
            install()
        }
    }
}

@Composable
private fun WarningLikeCard(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    CtrlCard(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, null, Modifier.padding(end = 14.dp), tint = CtrlColors.AccentText)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text)
        }
    }
}

private const val PHASE_DOWNLOAD = 0
private const val PHASE_INSTALL = 1
private const val PHASE_RESTART = 2
