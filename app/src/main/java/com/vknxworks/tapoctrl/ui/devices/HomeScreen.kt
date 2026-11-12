package com.vknxworks.tapoctrl.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.data.DeviceState
import com.vknxworks.tapoctrl.data.SavedDevice
import com.vknxworks.tapoctrl.ui.components.CtrlCard
import com.vknxworks.tapoctrl.ui.components.CtrlHeroCard
import com.vknxworks.tapoctrl.ui.components.CtrlSquareButton
import com.vknxworks.tapoctrl.ui.components.MessageView
import com.vknxworks.tapoctrl.ui.components.StatusTag
import com.vknxworks.tapoctrl.ui.components.TabTitle
import com.vknxworks.tapoctrl.ui.nav.AccountRoute
import com.vknxworks.tapoctrl.ui.nav.AddRoute
import com.vknxworks.tapoctrl.ui.nav.DeviceRoute
import com.vknxworks.tapoctrl.ui.nav.LocalNavigator
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import com.vknxworks.tapoctrl.ui.theme.CtrlType
import com.vknxworks.tapoctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/** Home: every device with its state and a big on/off button. Refreshes on return and on pull. */
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val devices by c.devices.devices.collectAsStateWithLifecycle()
    val states by c.devices.states.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val accountSaved by c.account.saved.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        val job = scope.launch { c.devices.refreshAll() }
        onPauseOrDispose { job.cancel() }
    }

    fun toggle(d: SavedDevice, s: DeviceState?) {
        val on = s?.info?.on ?: return
        scope.launch {
            c.devices.setOn(d.id, !on)?.let { toast(context, errorMessage(context, it)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabTitle(
            stringResource(R.string.home_title),
            Modifier.statusBarsPadding(),
            label = if (devices.isEmpty()) null else pluralStringResource(R.plurals.device_count, devices.size, devices.size),
            trailing = {
                if (devices.isNotEmpty()) {
                    CtrlSquareButton(
                        if (settings.grid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
                        stringResource(if (settings.grid) R.string.show_list else R.string.show_grid),
                        onClick = { c.appScope.launch { c.settings.update { it.copy(grid = !it.grid) } } },
                    )
                }
                CtrlSquareButton(Icons.Outlined.Add, stringResource(R.string.add_device), onClick = { nav.navigate(AddRoute) })
            },
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    try {
                        c.devices.refreshAll()
                    } finally {
                        refreshing = false
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (devices.isEmpty()) {
                Column(Modifier.fillMaxSize()) {
                    if (!accountSaved) AccountPrompt(Modifier.padding(horizontal = Gutter, vertical = 4.dp)) { nav.navigate(AccountRoute) }
                    MessageView(
                        Icons.Outlined.Lightbulb,
                        stringResource(R.string.empty_title),
                        stringResource(R.string.empty_body),
                        actionLabel = stringResource(R.string.add_device),
                        onAction = { nav.navigate(AddRoute) },
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (settings.grid) 2 else 1),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!accountSaved) {
                        item(span = { GridItemSpan(maxLineSpan) }) { AccountPrompt(Modifier) { nav.navigate(AccountRoute) } }
                    }
                    items(devices, key = { it.id }) { d ->
                        val s = states[d.id]
                        DeviceTile(d, s, compact = settings.grid, onOpen = { nav.navigate(DeviceRoute(d.id)) }, onToggle = { toggle(d, s) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountPrompt(modifier: Modifier, onClick: () -> Unit) {
    CtrlHeroCard(stringResource(R.string.account_title), modifier.fillMaxWidth(), onClick = onClick) {
        Text(stringResource(R.string.account_prompt_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.account_prompt_body), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceTile(d: SavedDevice, s: DeviceState?, compact: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val (status, active) = statusText(s)
    val kind = d.kind(s)
    val canToggle = s?.info?.on != null && s.error == null
    val sub = listOf(s?.info?.model ?: d.model, d.ip).filter { it.isNotBlank() }.joinToString(" · ")
    CtrlCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        if (compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Icon(kindIcon(kind), null, Modifier.size(24.dp), tint = if (active) CtrlColors.AccentText else CtrlColors.Muted)
                Spacer(Modifier.weight(1f))
                PowerButton(s?.info?.on, canToggle, onToggle)
            }
            Spacer(Modifier.height(14.dp))
            Text(d.label(s), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            StatusTag(status, active)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(kind), null, Modifier.size(24.dp), tint = if (active) CtrlColors.AccentText else CtrlColors.Muted)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.label(s), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = CtrlType.Data, color = CtrlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    StatusTag(status, active)
                }
                Spacer(Modifier.width(12.dp))
                PowerButton(s?.info?.on, canToggle, onToggle, size = 64.dp)
            }
        }
    }
}
