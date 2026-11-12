package com.vknxworks.tapoctrl.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.data.AddResult
import com.vknxworks.tapoctrl.net.LanAddress
import com.vknxworks.tapoctrl.tapo.Discovery
import com.vknxworks.tapoctrl.tapo.Found
import com.vknxworks.tapoctrl.ui.components.CtrlButton
import com.vknxworks.tapoctrl.ui.components.CtrlTextField
import com.vknxworks.tapoctrl.ui.components.Group
import com.vknxworks.tapoctrl.ui.components.GroupRow
import com.vknxworks.tapoctrl.ui.components.ScreenTopBar
import com.vknxworks.tapoctrl.ui.components.WarningCard
import com.vknxworks.tapoctrl.ui.nav.LocalNavigator
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import com.vknxworks.tapoctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/** Add a device by its address, or scan this phone's /24 subnet for Tapo devices. */
@Composable
fun AddDeviceScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val saved by c.devices.devices.collectAsStateWithLifecycle()
    var ip by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var noNetwork by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<Found>() }
    val self = remember(scanning) { Discovery.localAddress(context) }

    fun add(address: String, hint: Found?) {
        if (adding) return
        adding = true
        message = null
        scope.launch {
            val r = c.devices.add(address, hint)
            adding = false
            when (r) {
                is AddResult.Added -> {
                    toast(context, context.getString(R.string.added, address))
                    if (hint == null) nav.back()
                }
                AddResult.AlreadyAdded -> message = context.getString(R.string.add_already)
                AddResult.NotLocal -> message = context.getString(R.string.err_not_local)
                AddResult.NoAnswer -> message = context.getString(R.string.add_no_answer, address)
            }
        }
    }

    fun scan() {
        val me = Discovery.localAddress(context)
        if (me == null) {
            noNetwork = true
            return
        }
        noNetwork = false
        scanning = true
        found.clear()
        scope.launch {
            try {
                val all = Discovery.scan(me) { f ->
                    scope.launch {
                        val i = found.indexOfFirst { it.ip == f.ip }
                        if (i >= 0) found[i] = f else found.add(f)
                        found.sortBy { Discovery.ipSortKey(it.ip) }
                    }
                }
                c.devices.updateAddresses(all)
            } finally {
                scanning = false
                scanned = true
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.add_device))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(bottom = 24.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CtrlTextField(
                    value = ip,
                    onValueChange = { v -> ip = v.filter { it.isDigit() || it == '.' }.take(15); message = null },
                    label = { Text(stringResource(R.string.ip_label)) },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (LanAddress.parse(ip) != null) add(ip, null) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                CtrlButton(
                    stringResource(if (adding) R.string.adding else R.string.add),
                    onClick = { add(ip, null) },
                    icon = Icons.Outlined.Add,
                    enabled = !adding && LanAddress.parse(ip) != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ip.isNotBlank() && ip.count { it == '.' } == 3 && LanAddress.parse(ip) == null) {
                    Text(stringResource(R.string.err_not_local), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Error)
                }
                message?.let { WarningCard(it, Icons.Outlined.WifiOff) }
                Text(stringResource(R.string.add_ip_hint), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
            }

            Group(
                stringResource(R.string.scan_title),
                footer = stringResource(R.string.scan_footer),
            ) {
                GroupRow(
                    stringResource(if (scanning) R.string.scanning else R.string.scan_network),
                    self?.let { stringResource(R.string.scan_subnet, LanAddress.text(it).substringBeforeLast('.') + ".1–254") }
                        ?: stringResource(R.string.scan_no_wifi),
                    icon = Icons.Outlined.Radar,
                    onClick = if (scanning) null else ({ scan() }),
                    showChevron = !scanning,
                )
                if (scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = CtrlColors.Accent, trackColor = CtrlColors.ProgressTrack)
                }
                found.forEach { f ->
                    val isSaved = saved.any { it.ip == f.ip || (f.mac != null && it.mac.equals(f.mac, true)) }
                    // Cameras speak a different protocol (and RTSP for video); not supported in this version.
                    val camera = f.type?.contains("CAMERA", ignoreCase = true) == true
                    GroupRow(
                        f.model ?: stringResource(R.string.found_device),
                        listOfNotNull(
                            f.ip,
                            f.type?.removePrefix("SMART.")?.lowercase(),
                            if (camera) stringResource(R.string.found_camera) else if (f.legacy) stringResource(R.string.found_legacy) else null,
                        ).joinToString(" · "),
                        icon = if (camera) Icons.Outlined.Videocam else Icons.Outlined.Router,
                        trailing = {
                            if (camera) {
                                Unit
                            } else if (isSaved) {
                                Icon(Icons.Outlined.Check, stringResource(R.string.added_short), tint = CtrlColors.AccentText)
                            } else {
                                TextButton(onClick = { add(f.ip, f) }, enabled = !adding) { Text(stringResource(R.string.add)) }
                            }
                        },
                    )
                }
                if (scanned && !scanning && found.isEmpty()) {
                    GroupRow(stringResource(R.string.scan_none), stringResource(R.string.scan_none_sub))
                }
            }
            if (noNetwork) WarningCard(stringResource(R.string.scan_no_wifi), Icons.Outlined.WifiOff, Modifier.padding(horizontal = Gutter))
        }
    }
}
