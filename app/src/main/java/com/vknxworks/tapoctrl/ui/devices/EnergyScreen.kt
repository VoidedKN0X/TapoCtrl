package com.vknxworks.tapoctrl.ui.devices

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.tapoctrl.R
import com.vknxworks.tapoctrl.container
import com.vknxworks.tapoctrl.tapo.EnergyBar
import com.vknxworks.tapoctrl.tapo.EnergyRange
import com.vknxworks.tapoctrl.tapo.EnergySeries
import com.vknxworks.tapoctrl.tapo.TapoException
import com.vknxworks.tapoctrl.ui.components.ScreenTopBar
import com.vknxworks.tapoctrl.ui.components.StatRow
import com.vknxworks.tapoctrl.ui.components.StatTile
import com.vknxworks.tapoctrl.ui.components.WarningCard
import com.vknxworks.tapoctrl.ui.theme.CtrlColors
import com.vknxworks.tapoctrl.ui.theme.CtrlShapes
import com.vknxworks.tapoctrl.ui.theme.CtrlType
import com.vknxworks.tapoctrl.ui.theme.Gutter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Energy use of a plug that measures it: today by the hour, the last 7 and 30 days by the day, the last 12 months
 * by the month, from the plug's own memory. Only reads; nothing on the plug changes.
 */
@Composable
fun EnergyScreen(id: String) {
    val context = LocalContext.current
    val c = context.container
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(EnergyRange.TODAY) }
    var series by remember { mutableStateOf<EnergySeries?>(null) }
    var error by remember { mutableStateOf<TapoException?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    // Reads only: get_energy_data (or get_energy_usage on older firmware), through the read-only path.
    LaunchedEffect(range, attempt) {
        loading = true
        error = null
        series = null
        try {
            series = c.devices.energyHistory(id, range)
        } catch (e: TapoException) {
            error = e
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.energy_history))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EnergyRange.entries.forEach { r ->
                    val selected = r == range
                    Text(
                        stringResource(rangeLabel(r)),
                        style = CtrlType.LabelStrong,
                        color = if (selected) CtrlColors.OnAccent else CtrlColors.Text,
                        modifier = Modifier.weight(1f).clip(CtrlShapes.Chip)
                            .background(if (selected) CtrlColors.Accent else CtrlColors.Raised)
                            .clickable { range = r }
                            .padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            val s = series
            val e = error
            when {
                loading -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CtrlColors.Accent) }
                e != null -> {
                    WarningCard(errorMessage(context, e), Icons.Outlined.ErrorOutline, Modifier.padding(horizontal = Gutter, vertical = 8.dp))
                    Text(
                        stringResource(R.string.energy_retry),
                        style = CtrlType.LabelStrong,
                        color = CtrlColors.AccentText,
                        modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 8.dp).clickable { attempt++ },
                    )
                }
                s != null -> EnergyContent(s, settings.tariffPerKwh, settings.currency)
            }
            Text(
                stringResource(R.string.energy_history_footer),
                style = MaterialTheme.typography.bodySmall,
                color = CtrlColors.Muted,
                modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 8.dp),
            )
        }
    }
}

private fun rangeLabel(r: EnergyRange) = when (r) {
    EnergyRange.TODAY -> R.string.energy_range_today
    EnergyRange.WEEK -> R.string.energy_range_week
    EnergyRange.MONTH -> R.string.energy_range_month
    EnergyRange.YEAR -> R.string.energy_range_year
}

private fun kwhText(wh: Double): String =
    if (wh < 1000) String.format(Locale.getDefault(), "%.0f Wh", wh) else String.format(Locale.getDefault(), "%.2f kWh", wh / 1000.0)

/** The bar's own label under the chart, and in full when it's tapped. */
private fun barLabel(range: EnergyRange, b: EnergyBar, full: Boolean): String {
    val loc = Locale.getDefault()
    return when (range) {
        EnergyRange.TODAY -> if (full) String.format(loc, "%02d:00–%02d:00", b.start.hour, (b.start.hour + 1) % 24) else b.start.hour.toString()
        EnergyRange.WEEK -> if (full) "${b.start.dayOfWeek.getDisplayName(TextStyle.FULL, loc)} ${b.start.dayOfMonth}" else b.start.dayOfWeek.getDisplayName(TextStyle.SHORT, loc)
        EnergyRange.MONTH -> if (full) "${b.start.dayOfMonth} ${b.start.month.getDisplayName(TextStyle.SHORT, loc)}" else b.start.dayOfMonth.toString()
        EnergyRange.YEAR -> if (full) "${b.start.month.getDisplayName(TextStyle.FULL, loc)} ${b.start.year}" else b.start.month.getDisplayName(TextStyle.NARROW, loc)
    }
}

@Composable
private fun EnergyContent(s: EnergySeries, price: Double?, currency: String) {
    var selected by remember(s) { mutableStateOf<Int?>(null) }
    val total = s.totalWh
    fun cost(wh: Double) = price?.let { String.format(Locale.getDefault(), "%.2f %s", wh / 1000.0 * it, currency) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp)) {
        StatRow(
            { m -> StatTile(stringResource(R.string.energy_total), kwhText(total), m, sub = cost(total)) },
            { m ->
                val sel = selected?.let { s.bars.getOrNull(it) }
                val shown = sel ?: s.peak
                StatTile(
                    stringResource(if (sel != null) R.string.energy_selected else R.string.energy_peak),
                    shown?.wh?.let { kwhText(it) } ?: "–",
                    m,
                    sub = shown?.let { barLabel(s.range, it, full = true) },
                )
            },
        )
        Spacer(Modifier.size(14.dp))
        if (!s.hasData) {
            Text(stringResource(R.string.energy_no_data), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Muted, modifier = Modifier.padding(4.dp))
        } else {
            BarChart(s, selected) { selected = if (selected == it) null else it }
        }
    }
}

/** Bars drawn with Compose Canvas: the accent for the tapped bar, a lighter fill for the rest, a top gridline. */
@Composable
private fun BarChart(s: EnergySeries, selected: Int?, onTap: (Int) -> Unit) {
    val fill = CtrlColors.ProgressFill
    val accent = CtrlColors.Accent
    val track = CtrlColors.ProgressTrack
    val grid = CtrlColors.Divider
    val max = (s.bars.maxOfOrNull { it.wh ?: 0.0 } ?: 0.0).coerceAtLeast(1.0)
    val n = s.bars.size
    val description = stringResource(R.string.energy_chart_desc, stringResource(rangeLabel(s.range)), kwhText(s.totalWh))
    Column {
        Text(kwhText(max), style = CtrlType.Label, color = CtrlColors.Muted)
        Canvas(
            Modifier.fillMaxWidth().height(200.dp).padding(top = 4.dp)
                .semantics { contentDescription = description }
                .pointerInput(s) {
                    detectTapGestures { o -> onTap((o.x / size.width * n).toInt().coerceIn(0, n - 1)) }
                },
        ) {
            val slot = size.width / n
            val gap = (slot * 0.22f).coerceAtMost(6.dp.toPx())
            val w = (slot - gap).coerceAtLeast(1f)
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            s.bars.forEachIndexed { i, b ->
                val x = i * slot + gap / 2
                val v = b.wh
                if (v == null) {
                    drawRoundRect(track, Offset(x, size.height - 2.dp.toPx()), Size(w, 2.dp.toPx()))
                } else {
                    val h = (v / max * size.height).toFloat().coerceAtLeast(if (v > 0) 2.dp.toPx() else 0f)
                    drawRoundRect(
                        if (i == selected) accent else fill,
                        Offset(x, size.height - h),
                        Size(w, h),
                        CornerRadius(minOf(w / 2, 3.dp.toPx())),
                    )
                }
            }
        }
        // A few labels under the chart, evenly spaced.
        val every = when (s.range) {
            EnergyRange.TODAY -> 6
            EnergyRange.WEEK -> 1
            EnergyRange.MONTH -> 5
            EnergyRange.YEAR -> 1
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            s.bars.forEachIndexed { i, b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % every == 0) Text(barLabel(s.range, b, full = false), style = CtrlType.Label, color = CtrlColors.Muted, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
