package com.vknxworks.tapoctrl.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Euro
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.data.lock.LockVault
import com.vknxworks.tapoctrl.data.settings.AppSettings
import com.vknxworks.tapoctrl.data.settings.LockTimeout
import com.vknxworks.tapoctrl.ui.components.CtrlAlertDialog
import com.vknxworks.tapoctrl.ui.components.CtrlTextField
import com.vknxworks.tapoctrl.ui.components.Group
import com.vknxworks.tapoctrl.ui.components.GroupChoice
import com.vknxworks.tapoctrl.ui.components.GroupRow
import com.vknxworks.tapoctrl.ui.components.GroupSwitch
import com.vknxworks.tapoctrl.ui.components.ScreenTopBar
import com.vknxworks.tapoctrl.ui.lock.AppLock
import com.vknxworks.tapoctrl.ui.lock.PinRequest
import com.vknxworks.tapoctrl.ui.lock.findActivity
import com.vknxworks.tapoctrl.ui.nav.AccountRoute
import com.vknxworks.tapoctrl.ui.nav.LocalNavigator
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import kotlinx.coroutines.launch

/** Settings, opened from the You page (tap the profile picture). TapoCtrl follows the phone's dark theme. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val accountSaved by c.account.saved.collectAsStateWithLifecycle()
    var editTariff by remember { mutableStateOf(false) }
    fun set(transform: (AppSettings) -> AppSettings) {
        scope.launch { c.settings.update(transform) }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.settings))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            Group(stringResource(R.string.group_devices)) {
                GroupRow(
                    stringResource(R.string.account_title),
                    stringResource(if (accountSaved) R.string.account_saved else R.string.account_not_saved),
                    icon = Icons.Outlined.Key,
                    showChevron = true,
                    onClick = { nav.navigate(AccountRoute) },
                )
                GroupChoice(
                    stringResource(R.string.layout),
                    listOf(true to stringResource(R.string.layout_grid), false to stringResource(R.string.layout_list)),
                    settings.grid,
                    icon = Icons.Outlined.GridView,
                ) { v -> set { it.copy(grid = v) } }
            }
            Group(stringResource(R.string.group_energy), footer = stringResource(R.string.tariff_footer)) {
                GroupRow(
                    stringResource(R.string.tariff),
                    settings.tariffPerKwh?.let { stringResource(R.string.tariff_value, settings.tariff, settings.currency) }
                        ?: stringResource(R.string.tariff_none),
                    icon = Icons.Outlined.Payments,
                    showChevron = true,
                    onClick = { editTariff = true },
                )
                GroupChoice(
                    stringResource(R.string.currency),
                    CURRENCIES.map { it to it },
                    settings.currency,
                    icon = Icons.Outlined.Euro,
                    fallbackLabel = settings.currency,
                ) { v -> set { it.copy(currency = v) } }
            }
            PrivacyAndSecurityGroup(settings)
        }
    }
    if (editTariff) {
        var text by remember { mutableStateOf(settings.tariff) }
        CtrlAlertDialog(
            onDismissRequest = { editTariff = false },
            title = { Text(stringResource(R.string.tariff), style = MaterialTheme.typography.titleLarge) },
            text = {
                CtrlTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' || it == ',' }.take(10) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tariff_hint, settings.currency)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { editTariff = false; set { it.copy(tariff = text.trim()) } }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editTariff = false }) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
        )
    }
}

private val CURRENCIES = listOf("€", "$", "£", "CHF", "kr", "zł", "Kč", "Ft", "¥", "₹", "R$", "A$", "C$")
/** Privacy and security: the shared Ctrl series app lock, then hiding TapoCtrl in screenshots. */
@Composable
private fun PrivacyAndSecurityGroup(settings: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val bioAvailable = remember { AppLock.biometricAvailable(context) }
    val lockOn = c.vault.enabled
    Group(stringResource(R.string.group_privacy), footer = stringResource(R.string.app_lock_footer)) {
        GroupSwitch(
            stringResource(R.string.app_lock),
            stringResource(R.string.app_lock_subtitle),
            checked = lockOn,
            icon = Icons.Outlined.Lock,
        ) { on -> AppLock.request = if (on) PinRequest.ENABLE else PinRequest.DISABLE }
        if (lockOn) {
            GroupRow(
                stringResource(R.string.lock_change_pin),
                stringResource(R.string.lock_change_pin_subtitle, LockVault.PIN_MIN),
                icon = Icons.Outlined.Password,
                showChevron = true,
                onClick = { AppLock.request = PinRequest.CHANGE },
            )
            GroupSwitch(
                stringResource(R.string.lock_fingerprint),
                stringResource(if (bioAvailable || c.vault.biometricEnabled) R.string.lock_fingerprint_subtitle else R.string.lock_fingerprint_unavailable),
                checked = c.vault.biometricEnabled,
                icon = Icons.Outlined.Fingerprint,
            ) { on ->
                if (!on) {
                    c.vault.disableBiometric()
                } else if (bioAvailable) {
                    val activity = context.findActivity()
                    val cipher = c.vault.biometricEnrolCipher()
                    if (activity == null || cipher == null) {
                        Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        AppLock.biometricPrompt(
                            activity,
                            context.getString(R.string.lock_fingerprint_enable_title),
                            context.getString(R.string.lock_fingerprint_enable_subtitle),
                            cipher,
                        ) { unlocked ->
                            if (!c.vault.finishBiometricEnrol(unlocked)) {
                                Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            GroupChoice(
                stringResource(R.string.lock_again),
                LockTimeout.entries.map { it to stringResource(it.label) },
                settings.lockTimeout,
                icon = Icons.Outlined.Timer,
            ) { t -> c.appScope.launch { c.settings.update { it.copy(lockTimeout = t) } } }
        }
        GroupSwitch(
            stringResource(R.string.hide_screenshots),
            stringResource(R.string.hide_screenshots_subtitle),
            checked = settings.hideScreenshots,
            icon = Icons.Outlined.VisibilityOff,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideScreenshots = on) } } }
        GroupSwitch(
            stringResource(R.string.hide_recents),
            stringResource(R.string.hide_recents_subtitle),
            checked = settings.hideRecents,
            icon = Icons.Outlined.Layers,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideRecents = on) } } }
    }
}
