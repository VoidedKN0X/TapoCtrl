package com.vknxworks.tapoctrl.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.ui.components.ConfirmDialog
import com.vknxworks.tapoctrl.ui.components.CtrlButton
import com.vknxworks.tapoctrl.ui.components.CtrlTextField
import com.vknxworks.tapoctrl.ui.components.Group
import com.vknxworks.tapoctrl.ui.components.GroupRow
import com.vknxworks.tapoctrl.ui.components.ScreenTopBar
import com.vknxworks.tapoctrl.ui.components.ctrlFieldColors
import com.vknxworks.tapoctrl.ui.lock.GuardWindow
import com.vknxworks.tapoctrl.ui.nav.LocalNavigator
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import com.vknxworks.tapoctrl.ui.theme.CtrlShapes
import com.vknxworks.tapoctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Your Tapo account. The email and password are turned into the two KLAP hashes on this phone and only those are
 * kept, encrypted with a Keystore key. TapoCtrl never sends them to TP-Link; only your devices see a hash of them.
 */
@Composable
fun AccountScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val saved by c.account.saved.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    // The password field is sensitive: no overlays from other apps while this page shows.
    GuardWindow(true, sensitive = true)

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.account_title))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(bottom = 24.dp)) {
            if (saved) {
                Group(footer = stringResource(R.string.account_saved_footer)) {
                    GroupRow(stringResource(R.string.account_saved), stringResource(R.string.account_saved_sub), icon = Icons.Outlined.Key)
                    GroupRow(
                        stringResource(R.string.account_forget),
                        icon = Icons.Outlined.DeleteOutline,
                        iconTint = CtrlColors.Error,
                        titleColor = CtrlColors.Error,
                        onClick = { confirmForget = true },
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (saved) R.string.account_replace_intro else R.string.account_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CtrlColors.Muted,
                )
                CtrlTextField(
                    value = email,
                    onValueChange = { email = it.take(254) },
                    label = { Text(stringResource(R.string.account_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(128) },
                    label = { Text(stringResource(R.string.account_password)) },
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(
                                if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                stringResource(if (show) R.string.hide_password else R.string.show_password),
                            )
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = CtrlShapes.Field,
                    colors = ctrlFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                CtrlButton(
                    stringResource(R.string.account_save),
                    onClick = {
                        busy = true
                        val e = email
                        val p = password
                        scope.launch {
                            val ok = withContext(Dispatchers.Default) { c.account.save(e, p) }
                            busy = false
                            if (ok) {
                                password = ""
                                email = ""
                                c.devices.resetSessions()
                                c.appScope.launch { c.devices.refreshAll() }
                                toast(context, context.getString(R.string.account_saved_toast))
                                nav.back()
                            } else {
                                toast(context, context.getString(R.string.account_save_failed))
                            }
                        }
                    },
                    enabled = !busy && email.contains('@') && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.account_privacy), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
            }
        }
    }
    if (confirmForget) {
        ConfirmDialog(
            stringResource(R.string.account_forget_title),
            stringResource(R.string.account_forget_body),
            stringResource(R.string.account_forget),
            onDismiss = { confirmForget = false },
        ) {
            confirmForget = false
            c.account.clear()
            c.devices.resetSessions()
        }
    }
}
