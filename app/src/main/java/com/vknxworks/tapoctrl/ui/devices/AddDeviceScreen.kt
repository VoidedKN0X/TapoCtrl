package com.vknxworks.tapoctrl.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.vknxworks.tapoctrl.net.LocalNetwork
import com.vknxworks.tapoctrl.tapo.Discovery
import com.vknxworks.tapoctrl.tapo.Found
import com.vknxworks.tapoctrl.tapo.ScanRange
import com.vknxworks.tapoctrl.ui.components.CtrlButton
import com.vknxworks.tapoctrl.ui.components.CtrlProgressBar
import com.vknxworks.tapoctrl.ui.components.CtrlTextField
import com.vknxworks.tapoctrl.ui.components.Group
import com.vknxworks.tapoctrl.ui.components.GroupRow
import com.vknxworks.tapoctrl.ui.components.ScreenTopBar
import com.vknxworks.tapoctrl.ui.components.WarningCard
import com.vknxworks.tapoctrl.ui.nav.LocalNavigator
import com.vknxworks.tapoctrl.ui.components.CtrlAlertDialog
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import com.vknxworks.tapoctrl.ui.theme.CtrlType
import com.vknxworks.tapoctrl.ui.theme.Gutter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.Inet4Address

/**
 * Add a device by its address, or scan for Tapo devices: this phone's Wi-Fi /24 (also with a VPN on), or a range
 * you type (for a network reached through a VPN such as a Tailscale subnet route). Scanning only reads.
 */
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
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    // Each scan has its own number, so answers from a stopped scan aren't shown under the next one.
    var scanId by remember { mutableIntStateOf(0) }
    var rangeDialog by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<Found>() }
    val self = remember(scanning) { LocalNetwork.wifiAddress(context) }
    var certificate by remember { mutableStateOf<AddResult.ConfirmCertificate?>(null) }

    fun handle(r: AddResult, address: String, hint: Found?) {
        when (r) {
            is AddResult.Added -> {
                toast(context, context.getString(R.string.added, address))
                if (hint == null) nav.back()
            }
            AddResult.AlreadyAdded -> message = context.getString(R.string.add_already)
            AddResult.NotLocal -> message = context.getString(R.string.err_not_local)
            AddResult.NoAnswer -> message = context.getString(R.string.add_no_answer, address)
            is AddResult.CameraNoAnswer -> message = context.getString(R.string.add_camera_no_answer, r.ip)
            // A camera: show its certificate's fingerprint and add it only once you confirm.
            is AddResult.ConfirmCertificate -> certificate = r
        }
    }

    fun add(address: String, hint: Found?) {
        if (adding) return
        adding = true
        message = null
        scope.launch {
            val r = c.devices.add(address, hint)
            adding = false
            handle(r, address, hint)
        }
    }

    fun addCamera(cert: AddResult.ConfirmCertificate) {
        if (adding) return
        adding = true
        message = null
        scope.launch {
            val r = c.devices.addCamera(cert.ip, cert.fingerprint, cert.hint)
            adding = false
            handle(r, cert.ip, cert.hint)
        }
    }

    /** Scans [targets]: only reads (discovery packet and the KLAP probe); see [Discovery]. */
    fun startScan(targets: List<Inet4Address>, broadcast: Inet4Address?, timing: Discovery.Timing) {
        if (scanning) return
        val id = scanId + 1
        scanId = id
        scanning = true
        scanned = false
        progress = 0 to targets.size
        found.clear()
        scanJob = scope.launch {
            try {
                val all = Discovery.scan(
                    targets, broadcast, timing,
                    onProgress = { done, total -> scope.launch { if (scanId == id) progress = done to total } },
                ) { f ->
                    scope.launch {
                        if (scanId != id) return@launch
                        val i = found.indexOfFirst { it.ip == f.ip }
                        if (i >= 0) found[i] = f else found.add(f)
                        found.sortBy { Discovery.ipSortKey(it.ip) }
                    }
                }
                c.devices.updateAddresses(all)
            } finally {
                if (scanId == id) {
                    scanning = false
                    scanned = true
                    scanJob = null
                }
            }
        }
    }

    /** The phone's own Wi-Fi /24 (also with a VPN on); without Wi-Fi, offer a range instead. */
    fun scanWifi() {
        val me = LocalNetwork.wifiAddress(context)
        if (me == null) {
            rangeDialog = true
            return
        }
        startScan(Discovery.subnet(me), Discovery.broadcastOf(me), Discovery.Timing.WIFI)
    }

    fun scanRange(range: ScanRange) {
        scope.launch { c.settings.update { it.copy(scanRange = range.text) } }
        startScan(range.hosts(), null, Discovery.Timing.RANGE)
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanId += 1
        scanning = false
        scanned = true
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
                    stringResource(R.string.scan_network),
                    self?.let { stringResource(R.string.scan_subnet, LanAddress.text(it).substringBeforeLast('.') + ".1–254") }
                        ?: stringResource(R.string.scan_no_wifi),
                    icon = Icons.Outlined.Radar,
                    onClick = if (scanning) null else ({ scanWifi() }),
                    showChevron = !scanning,
                )
                GroupRow(
                    stringResource(R.string.scan_range),
                    settings.scanRange.takeIf { it.isNotBlank() }?.let { stringResource(R.string.scan_range_sub, it) }
                        ?: stringResource(R.string.scan_range_sub_none),
                    icon = Icons.Outlined.Lan,
                    onClick = if (scanning) null else ({ rangeDialog = true }),
                    showChevron = !scanning,
                )
                if (scanning) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val (done, total) = progress
                        CtrlProgressBar(if (total > 0) done.toFloat() / total else 0f, color = CtrlColors.Accent)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.scan_progress, done, total),
                                style = MaterialTheme.typography.bodySmall,
                                color = CtrlColors.Muted,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { stopScan() }) { Text(stringResource(R.string.scan_stop), color = CtrlColors.AccentText) }
                        }
                    }
                }
                found.forEach { f ->
                    val isSaved = saved.any { it.ip == f.ip || (f.mac != null && it.mac.equals(f.mac, true)) }
                    // Cameras: added after you confirm their certificate (no live video).
                    val camera = f.camera
                    GroupRow(
                        f.model ?: stringResource(R.string.found_device),
                        listOfNotNull(
                            f.ip,
                            f.type?.removePrefix("SMART.")?.lowercase(),
                            if (camera) stringResource(R.string.found_camera) else if (f.legacy) stringResource(R.string.found_legacy) else null,
                        ).joinToString(" · "),
                        icon = if (camera) Icons.Outlined.Videocam else Icons.Outlined.Router,
                        trailing = {
                            if (isSaved) {
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
        }
    }
    if (rangeDialog) {
        ScanRangeDialog(
            initial = settings.scanRange.ifBlank { self?.let { LanAddress.text(it).substringBeforeLast('.') + ".0/24" } ?: "" },
            onDismiss = { rangeDialog = false },
            onScan = { range -> rangeDialog = false; scanRange(range) },
        )
    }
    certificate?.let { cert ->
        CtrlAlertDialog(
            onDismissRequest = { certificate = null },
            title = { Text(stringResource(R.string.cert_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.cert_body, cert.ip), style = MaterialTheme.typography.bodyMedium)
                    Text(cert.fingerprint, style = CtrlType.Data, color = CtrlColors.Text)
                    Text(stringResource(R.string.cert_note), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = { certificate = null; addCamera(cert) }) { Text(stringResource(R.string.cert_trust), color = CtrlColors.AccentText) }
            },
            dismissButton = { TextButton(onClick = { certificate = null }) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
            guard = true,
        )
    }
}

/** Asks for the range to scan, such as 192.168.1.0/24, and checks it (private, at most a /22). */
@Composable
private fun ScanRangeDialog(initial: String, onDismiss: () -> Unit, onScan: (ScanRange) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val parsed = remember(text) { ScanRange.parse(text) }
    val error = when (parsed) {
        is ScanRange.Parsed.Ok -> null
        ScanRange.Parsed.Invalid -> if (text.isBlank()) null else stringResource(R.string.scan_range_invalid)
        ScanRange.Parsed.TooLarge -> stringResource(R.string.scan_range_too_large)
        ScanRange.Parsed.NotPrivate -> stringResource(R.string.scan_range_not_private)
    }
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_range), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.scan_range_body), style = MaterialTheme.typography.bodyMedium)
                CtrlTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' || it == '/' }.take(18) },
                    label = { Text(stringResource(R.string.scan_range_label)) },
                    placeholder = { Text("192.168.1.0/24") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { (parsed as? ScanRange.Parsed.Ok)?.let { onScan(it.range) } }),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = CtrlColors.Error)
                    parsed is ScanRange.Parsed.Ok -> Text(
                        stringResource(R.string.scan_range_count, parsed.range.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = CtrlColors.Muted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { (parsed as? ScanRange.Parsed.Ok)?.let { onScan(it.range) } }, enabled = parsed is ScanRange.Parsed.Ok) {
                Text(stringResource(R.string.scan_start), color = if (parsed is ScanRange.Parsed.Ok) CtrlColors.AccentText else CtrlColors.Muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
    )
}
