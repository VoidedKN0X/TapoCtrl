package com.vknxworks.tapoctrl.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vknxworks.tapoctrl.BuildConfig
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.data.settings.AppSettings
import com.vknxworks.tapoctrl.data.settings.SettingsFile
import com.vknxworks.tapoctrl.data.settings.SettingsFileContent
import com.vknxworks.tapoctrl.data.settings.SettingsFileException
import com.vknxworks.tapoctrl.ui.components.CtrlAlertDialog
import com.vknxworks.tapoctrl.ui.components.Group
import com.vknxworks.tapoctrl.ui.components.GroupRow
import com.vknxworks.tapoctrl.ui.lock.AppLock
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime

/** Export settings / Import settings: a JSON file with TapoCtrl's settings and device list, never the account (see [SettingsFile]). */
@Composable
fun SettingsFileGroup(settings: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<SettingsFileContent?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = SettingsFile.export(c.settings.settings.value, c.devices.devices.value, BuildConfig.VERSION_NAME, OffsetDateTime.now().toString())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("no stream")
                }
            }.isSuccess
            Toast.makeText(context, if (ok) R.string.settings_file_saved else R.string.settings_file_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > SettingsFile.MAX_BYTES) throw SettingsFileException(newer = false)
                        }
                        out.toString(Charsets.UTF_8.name())
                    } ?: throw IOException("no stream")
                }
                SettingsFile.parse(text)
            }.onSuccess { pending = it }.onFailure { e ->
                val msg = if (e is SettingsFileException && e.newer) R.string.settings_file_too_new else R.string.settings_file_not_valid
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    Group(stringResource(R.string.settings_file_group), footer = stringResource(R.string.settings_file_footer)) {
        GroupRow(
            stringResource(R.string.settings_file_export),
            stringResource(R.string.settings_file_export_sub),
            Icons.Outlined.FileDownload,
            onClick = { AppLock.expectReturn(); save.launch("tapoctrl-settings-${LocalDate.now()}.json") },
        )
        GroupRow(
            stringResource(R.string.settings_file_import),
            stringResource(R.string.settings_file_import_sub),
            Icons.Outlined.FileUpload,
            onClick = { AppLock.expectReturn(); open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        )
    }

    pending?.let { f ->
        val after = SettingsFile.applied(f, settings)
        val changes = buildList {
            fun onOff(v: Boolean) = context.getString(if (v) R.string.settings_file_on else R.string.settings_file_off)
            if (after.grid != settings.grid) add(context.getString(R.string.settings_file_change, context.getString(R.string.settings_file_layout), context.getString(if (settings.grid) R.string.settings_file_grid else R.string.settings_file_list), context.getString(if (after.grid) R.string.settings_file_grid else R.string.settings_file_list)))
            if (after.tariff != settings.tariff) add(context.getString(R.string.settings_file_change, context.getString(R.string.settings_file_tariff), settings.tariff.ifBlank { "–" }, after.tariff.ifBlank { "–" }))
            if (after.currency != settings.currency) add(context.getString(R.string.settings_file_change, context.getString(R.string.settings_file_currency), settings.currency, after.currency))
            if (after.lockTimeout != settings.lockTimeout) add(context.getString(R.string.settings_file_change, context.getString(R.string.lock_again), context.getString(settings.lockTimeout.label), context.getString(after.lockTimeout.label)))
            if (after.hideScreenshots != settings.hideScreenshots) add(context.getString(R.string.settings_file_change, context.getString(R.string.hide_screenshots), onOff(settings.hideScreenshots), onOff(after.hideScreenshots)))
            if (after.hideRecents != settings.hideRecents) add(context.getString(R.string.settings_file_change, context.getString(R.string.hide_recents), onOff(settings.hideRecents), onOff(after.hideRecents)))
            val merge = SettingsFile.merge(f, c.devices.devices.value) { "" }
            if (merge.added > 0) add(context.resources.getQuantityString(R.plurals.settings_file_devices_added, merge.added, merge.added))
            if (merge.roomsChanged > 0) add(context.resources.getQuantityString(R.plurals.settings_file_rooms_changed, merge.roomsChanged, merge.roomsChanged))
        }
        CtrlAlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.settings_file_import_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    if (changes.isEmpty()) stringResource(R.string.settings_file_no_changes)
                    else stringResource(R.string.settings_file_import_body, f.createdAt?.take(10) ?: "?", changes.joinToString("\n")),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                if (changes.isNotEmpty()) {
                    TextButton(onClick = {
                        pending = null
                        c.appScope.launch {
                            c.settings.update { SettingsFile.applied(f, it) }
                            if (!f.devices.isNullOrEmpty()) {
                                c.devices.importDevices { list -> SettingsFile.merge(f, list) { java.util.UUID.randomUUID().toString() }.list }
                            }
                            Toast.makeText(context, R.string.settings_file_imported, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.settings_file_replace), color = CtrlColors.AccentText) }
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(if (changes.isEmpty()) R.string.settings_file_close else R.string.cancel), color = CtrlColors.Text)
                }
            },
        )
    }
}
