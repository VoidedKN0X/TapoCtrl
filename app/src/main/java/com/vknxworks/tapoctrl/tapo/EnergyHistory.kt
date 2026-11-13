package com.vknxworks.tapoctrl.tapo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The four energy graphs. */
enum class EnergyRange { TODAY, WEEK, MONTH, YEAR }

/** One bar: the hour, day or month it starts at (device local time), and its energy in Wh (null: no data). */
data class EnergyBar(val start: LocalDateTime, val wh: Double?)

data class EnergySeries(val range: EnergyRange, val bars: List<EnergyBar>) {
    val totalWh: Double get() = bars.sumOf { it.wh ?: 0.0 }
    val peak: EnergyBar? get() = bars.filter { it.wh != null }.maxByOrNull { it.wh!! }
    val hasData: Boolean get() = bars.any { (it.wh ?: 0.0) > 0.0 }
}

/**
 * One get_energy_data request (read-only). Tapo plugs answer in fixed windows: hourly for up to 8 days,
 * daily from the first day of a quarter, monthly from the first day of a year. Dates are the device's local
 * dates, sent as midnight in seconds as if UTC (what the open-source Tapo clients send).
 */
data class EnergyRequest(val start: LocalDate, val end: LocalDate, val interval: Int) {
    fun params(): JsonObject = buildJsonObject {
        put("start_timestamp", seconds(start))
        put("end_timestamp", seconds(end))
        put("interval", interval)
    }

    /** When value number [i] of the answer starts. */
    fun slot(i: Int): LocalDateTime = when (interval) {
        HOURLY -> start.atStartOfDay().plusHours(i.toLong())
        DAILY -> start.plusDays(i.toLong()).atStartOfDay()
        else -> start.plusMonths(i.toLong()).atStartOfDay()
    }

    companion object {
        const val HOURLY = 60
        const val DAILY = 1440
        const val MONTHLY = 43200

        fun seconds(d: LocalDate): Long = d.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    }
}

object EnergyHistory {
    /** At most this many values are read from one answer (a year of hours is far more than any window). */
    const val MAX_VALUES = 9000

    fun quarterStart(d: LocalDate): LocalDate = LocalDate.of(d.year, ((d.monthValue - 1) / 3) * 3 + 1, 1)

    /** The requests for one graph, ending [today]. */
    fun requests(range: EnergyRange, today: LocalDate): List<EnergyRequest> = when (range) {
        EnergyRange.TODAY -> listOf(EnergyRequest(today, today, EnergyRequest.HOURLY))
        EnergyRange.WEEK -> listOf(EnergyRequest(today.minusDays(6), today, EnergyRequest.HOURLY))
        EnergyRange.MONTH -> listOf(quarterStart(today.minusDays(29)), quarterStart(today)).distinct()
            .map { EnergyRequest(it, it, EnergyRequest.DAILY) }
        EnergyRange.YEAR -> listOf(LocalDate.of(today.year - 1, 1, 1), LocalDate.of(today.year, 1, 1))
            .filter { !it.isBefore(LocalDate.of(today.minusMonths(11).year, 1, 1)) }
            .map { EnergyRequest(it, it, EnergyRequest.MONTHLY) }
    }

    /** The Wh values of a get_energy_data answer ("data": [...]). */
    fun parseData(r: JsonElement): List<Double?> {
        val arr = ((r as? JsonObject)?.get("data") as? JsonArray) ?: return emptyList()
        return arr.take(MAX_VALUES).map { v -> (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0 && it.isFinite() } }
    }

    /** The device's own clock, from an answer's "local_time" ("2026-11-13 14:05:00"), if it sends one. */
    fun localTime(r: JsonElement): LocalDateTime? {
        val s = ((r as? JsonObject)?.get("local_time") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return runCatching { LocalDateTime.parse(s.trim().replace(' ', 'T')) }.getOrNull()
    }

    /** Puts the answers to [requests] together into the bars of [range]. */
    fun assemble(range: EnergyRange, today: LocalDate, answers: List<Pair<EnergyRequest, List<Double?>>>): EnergySeries {
        val values = HashMap<LocalDateTime, Double?>()
        for ((req, data) in answers) data.forEachIndexed { i, v -> values[req.slot(i)] = v }
        val bars = when (range) {
            EnergyRange.TODAY -> (0 until 24).map { h -> today.atStartOfDay().plusHours(h.toLong()).let { EnergyBar(it, values[it]) } }
            EnergyRange.WEEK -> (6 downTo 0).map { back ->
                val day = today.minusDays(back.toLong())
                val hours = (0 until 24).map { values[day.atStartOfDay().plusHours(it.toLong())] }
                EnergyBar(day.atStartOfDay(), if (hours.all { it == null }) null else hours.sumOf { it ?: 0.0 })
            }
            EnergyRange.MONTH -> (29 downTo 0).map { back -> today.minusDays(back.toLong()).atStartOfDay().let { EnergyBar(it, values[it]) } }
            EnergyRange.YEAR -> (11 downTo 0).map { back -> today.withDayOfMonth(1).minusMonths(back.toLong()).atStartOfDay().let { EnergyBar(it, values[it]) } }
        }
        return EnergySeries(range, bars)
    }

    /**
     * Older firmware has no get_energy_data, but get_energy_usage carries the history itself: past24h (24
     * hours up to now), past7d (7 days × 24 hours), past30d (30 days) and past1y (12 months), oldest first.
     * Null when this answer doesn't have the list for [range].
     */
    fun fromUsage(range: EnergyRange, usage: JsonElement, now: LocalDateTime): EnergySeries? {
        val o = usage as? JsonObject ?: return null
        fun list(k: String): List<Double?>? = (o[k] as? JsonArray)?.take(MAX_VALUES)?.map { (it as? JsonPrimitive)?.doubleOrNull }
        val today = now.toLocalDate()
        return when (range) {
            EnergyRange.TODAY -> {
                val l = list("past24h") ?: return null
                // The last value is the current hour; keep the hours of today.
                val hourNow = now.withMinute(0).withSecond(0).withNano(0)
                val byHour = l.reversed().mapIndexed { i, v -> hourNow.minusHours(i.toLong()) to v }.toMap()
                EnergySeries(range, (0 until 24).map { h -> today.atStartOfDay().plusHours(h.toLong()).let { EnergyBar(it, if (it.isAfter(hourNow)) null else byHour[it]) } })
            }
            EnergyRange.WEEK -> {
                val days = (o["past7d"] as? JsonArray) ?: return null
                val sums = days.take(7).map { d ->
                    val hours = (d as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }
                    if (hours == null || hours.all { it == null }) null else hours.sumOf { it ?: 0.0 }
                }
                EnergySeries(range, alignRight(sums, 7) { i -> today.minusDays((6 - i).toLong()).atStartOfDay() })
            }
            EnergyRange.MONTH -> {
                val l = list("past30d") ?: return null
                EnergySeries(range, alignRight(l, 30) { i -> today.minusDays((29 - i).toLong()).atStartOfDay() })
            }
            EnergyRange.YEAR -> {
                val l = list("past1y") ?: return null
                val month = today.withDayOfMonth(1)
                EnergySeries(range, alignRight(l, 12) { i -> month.minusMonths((11 - i).toLong()).atStartOfDay() })
            }
        }
    }

    /** [n] bars from oldest-first [values] whose last value is the newest bar; missing older ones are empty. */
    private fun alignRight(values: List<Double?>, n: Int, at: (Int) -> LocalDateTime): List<EnergyBar> =
        (0 until n).map { i -> EnergyBar(at(i), values.getOrNull(values.size - n + i)) }
    /** Reads one graph through the read-only view only. */
    suspend fun read(c: TapoCaller, range: EnergyRange, now: LocalDateTime): EnergySeries {
        val r = c.readOnly()
        val today = now.toLocalDate()
        return try {
            val answers = requests(range, today).map { req -> req to parseData(r.call(TapoMethods.GET_ENERGY_DATA, req.params())) }
            assemble(range, today, answers)
        } catch (e: TapoException.DeviceError) {
            // No get_energy_data on this firmware: use the lists in get_energy_usage, if it has them.
            fromUsage(range, r.call(TapoMethods.GET_ENERGY_USAGE, null), now) ?: throw e
        }
    }
}
