package com.vknxworks.tapoctrl.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import com.vknxworks.tapoctrl.ui.components.ChoiceSheet
import com.vknxworks.tapoctrl.ui.components.NameDialog
import com.vknxworks.tapoctrl.ui.theme.CtrlShapes
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.data.DeviceState
import com.vknxworks.tapoctrl.data.SavedDevice
import com.vknxworks.tapoctrl.ui.components.ConfirmDialog
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
    val loaded by c.devices.loaded.collectAsStateWithLifecycle()
    val unreadable by c.devices.unreadable.collectAsStateWithLifecycle()
    var confirmStartEmpty by remember { mutableStateOf(false) }
    val states by c.devices.states.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val accountSaved by c.account.saved.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    var roomFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var roomFor by remember { mutableStateOf<SavedDevice?>(null) }
    val rooms = remember(devices) { devices.map { it.room }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() } }
    val room = roomFilter?.takeIf { it in rooms }
    val shown = remember(devices, room) { devices.filter { room == null || it.room == room } }

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
                if (devices.size > 1) {
                    CtrlSquareButton(
                        if (arranging) Icons.Outlined.Check else Icons.Outlined.SwapVert,
                        stringResource(if (arranging) R.string.arrange_done else R.string.arrange),
                        onClick = { arranging = !arranging },
                    )
                }
                if (devices.isNotEmpty() && !arranging) {
                    CtrlSquareButton(
                        if (settings.grid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
                        stringResource(if (settings.grid) R.string.show_list else R.string.show_grid),
                        onClick = { c.appScope.launch { c.settings.update { it.copy(grid = !it.grid) } } },
                    )
                }
                // Not while the saved list can't be opened: nothing could be saved next to it.
                if (!unreadable) {
                    CtrlSquareButton(Icons.Outlined.Add, stringResource(R.string.add_device), onClick = { nav.navigate(AddRoute) })
                }
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
            if (!loaded) {
                // The saved list is still being read: nothing yet, instead of "no devices" for a moment.
                Box(Modifier.fillMaxSize())
            } else if (unreadable) {
                // The saved list is encrypted and can't be opened. Nothing was deleted or changed: say so, offer
                // to try again, and only start empty when asked to.
                Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                    MessageView(
                        Icons.Outlined.Lock,
                        stringResource(R.string.unreadable_title),
                        stringResource(R.string.unreadable_body),
                        actionLabel = stringResource(R.string.unreadable_retry),
                        onAction = { c.devices.retry() },
                    )
                    TextButton(onClick = { confirmStartEmpty = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
                        Text(stringResource(R.string.unreadable_start_empty), color = CtrlColors.Muted)
                    }
                }
            } else if (devices.isEmpty()) {
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
                    columns = GridCells.Fixed(if (settings.grid && !arranging) 2 else 1),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!accountSaved) {
                        item(span = { GridItemSpan(maxLineSpan) }) { AccountPrompt(Modifier) { nav.navigate(AccountRoute) } }
                    }
                    if (rooms.isNotEmpty() && !arranging) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "rooms") {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RoomChip(stringResource(R.string.room_all), room == null) { roomFilter = null }
                                rooms.forEach { r -> RoomChip(r, room == r) { roomFilter = r } }
                            }
                        }
                    }
                    if (arranging) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "arrange-hint") {
                            Text(stringResource(R.string.arrange_hint), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
                        }
                        items(devices, key = { it.id }) { d ->
                            val i = devices.indexOf(d)
                            ArrangeRow(
                                d.label(states[d.id]),
                                d.room,
                                onUp = if (i > 0) ({ scope.launch { c.devices.move(d.id, -1) } }) else null,
                                onDown = if (i < devices.lastIndex) ({ scope.launch { c.devices.move(d.id, +1) } }) else null,
                                onRoom = { roomFor = d },
                            )
                        }
                    } else {
                        items(shown, key = { it.id }) { d ->
                            val s = states[d.id]
                            DeviceTile(d, s, compact = settings.grid, onOpen = { nav.navigate(DeviceRoute(d.id)) }, onToggle = { toggle(d, s) })
                        }
                    }
                }
            }
        }
    }
    roomFor?.let { d ->
        RoomPicker(d.room, rooms, onDismiss = { roomFor = null }) { picked ->
            roomFor = null
            scope.launch { c.devices.setRoom(d.id, picked) }
        }
    }
    if (confirmStartEmpty) {
        StartEmptyDialog(onDismiss = { confirmStartEmpty = false }) {
            confirmStartEmpty = false
            scope.launch { c.devices.startEmpty(keep = true) }
        }
    }
}

@Composable
private fun StartEmptyDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.unreadable_start_empty_title),
        body = stringResource(R.string.unreadable_start_empty_body),
        confirm = stringResource(R.string.unreadable_start_empty),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
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

@Composable
private fun RoomChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = CtrlType.Data) },
        shape = CtrlShapes.Chip,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = CtrlColors.Surface,
            labelColor = CtrlColors.Text,
            selectedContainerColor = CtrlColors.Accent,
            selectedLabelColor = CtrlColors.OnAccent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = CtrlColors.ControlOutline,
            selectedBorderColor = CtrlColors.Accent,
        ),
    )
}

/** "Arrange": one device with up, down and its room. Nothing is sent to the device. */
@Composable
private fun ArrangeRow(name: String, room: String, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onRoom: () -> Unit) {
    CtrlCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(room.ifBlank { stringResource(R.string.room_none) }, style = CtrlType.Data, color = CtrlColors.Muted, maxLines = 1)
            }
            CtrlSquareButton(Icons.Outlined.MeetingRoom, stringResource(R.string.room), onClick = onRoom, size = 40.dp)
            Spacer(Modifier.width(6.dp))
            CtrlSquareButton(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.move_up), onClick = { onUp?.invoke() }, size = 40.dp, enabled = onUp != null)
            Spacer(Modifier.width(6.dp))
            CtrlSquareButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.move_down), onClick = { onDown?.invoke() }, size = 40.dp, enabled = onDown != null)
        }
    }
}

/** Pick a room you already use, no room, or name a new one. */
@Composable
private fun RoomPicker(current: String, rooms: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    val none = stringResource(R.string.room_none)
    val new = stringResource(R.string.room_new)
    if (naming) {
        NameDialog(stringResource(R.string.room_new_title), "", onDismiss = onDismiss) { name -> onPick(name) }
    } else {
        ChoiceSheet(
            stringResource(R.string.room),
            listOf(ROOM_NONE to none) + rooms.map { it to it } + listOf(ROOM_NEW to new),
            current.ifBlank { ROOM_NONE },
            onDismiss = onDismiss,
        ) { picked ->
            when (picked) {
                ROOM_NEW -> naming = true
                ROOM_NONE -> onPick("")
                else -> onPick(picked)
            }
        }
    }
}

private const val ROOM_NONE = "\u0000none"
private const val ROOM_NEW = "\u0000new"
