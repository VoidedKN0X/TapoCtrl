package com.vknxworks.tapoctrl.tapo

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Export as CSV" on the energy screen: the bars shown, one per line, for a spreadsheet. Comma-separated with a
 * dot as the decimal mark (so every spreadsheet reads it the same way), times in the device's local time.
 */
object EnergyCsv {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun write(series: EnergySeries, pricePerKwh: Double?, currency: String): String = buildString {
        val withCost = pricePerKwh != null
        append("start,energy_wh,energy_kwh")
        if (withCost) append(",cost_").append(currency.filter { it.isLetterOrDigit() }.ifEmpty { "cost" })
        append("\r\n")
        for (b in series.bars) {
            append(TIME.format(b.start)).append(',')
            val wh = b.wh
            if (wh != null) {
                append(String.format(Locale.ROOT, "%.0f", wh)).append(',')
                append(String.format(Locale.ROOT, "%.3f", wh / 1000.0))
                if (withCost) append(',').append(String.format(Locale.ROOT, "%.2f", wh / 1000.0 * pricePerKwh!!))
            } else {
                append(',')
                if (withCost) append(',')
            }
            append("\r\n")
        }
    }
}
