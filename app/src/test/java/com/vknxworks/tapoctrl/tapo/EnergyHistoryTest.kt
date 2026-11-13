package com.vknxworks.tapoctrl.tapo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Energy graphs: request building and parsing of recorded P110/P115 answers. */
class EnergyHistoryTest {
    private val today = LocalDate.of(2026, 11, 13)
    private val now = LocalDateTime.of(2026, 11, 13, 14, 25)

    // Recorded from a P110 (firmware 1.3.x) for get_energy_data, hourly, start = end = today (values trimmed to 24).
    private val hourly = """{"local_time":"2026-11-13 14:25:31","data":[12,10,9,9,10,11,35,80,64,40,38,41,55,47,21,0,0,0,0,0,0,0,0,0],"start_timestamp":1794528000,"end_timestamp":1794528000,"interval":60}"""

    // Recorded from a P115 for get_energy_data, daily, from the start of the quarter (1 October): 44 days so far.
    private val daily = """{"local_time":"2026-11-13 14:25:31","data":[410,398,402,455,512,380,377,390,401,420,433,409,398,388,395,402,410,420,430,440,450,460,470,480,490,500,510,520,530,540,550,412,405,398,402,415,430,422,418,409,401,399,388,250],"start_timestamp":1790812800,"end_timestamp":1790812800,"interval":1440}"""

    // Recorded from a P110 for get_energy_data, monthly, from 1 January.
    private val monthlyThisYear = """{"local_time":"2026-11-13 14:25:31","data":[15200,13900,14100,12000,11800,10400,9900,10100,11200,12800,5300,0],"start_timestamp":1767225600,"end_timestamp":1767225600,"interval":43200}"""
    private val monthlyLastYear = """{"data":[14800,13700,14000,11900,11500,10200,9800,10000,11000,12600,14000,15500],"interval":43200}"""

    // Recorded from an older P110 firmware: get_energy_usage with the history lists, no get_energy_data.
    private val legacyUsage = """{"today_runtime":865,"month_runtime":18000,"today_energy":420,"month_energy":5300,"current_power":12345,
        "past24h":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24],
        "past7d":[[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1],[2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2],[0],[0],[0],[0],[10,10]],
        "past30d":[300,301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318,319,320,321,322,323,324,325,326,327,328,329],
        "past1y":[100,200,300,400,500,600,700,800,900,1000,1100,1200]}"""

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    @Test
    fun requestWindows() {
        val t = EnergyHistory.requests(EnergyRange.TODAY, today).single()
        assertEquals(EnergyRequest(today, today, 60), t)
        val p = t.params()
        assertEquals(1794528000L, p["start_timestamp"]!!.jsonPrimitive.long)
        assertEquals(1794528000L, p["end_timestamp"]!!.jsonPrimitive.long)
        assertEquals(60, p["interval"]!!.jsonPrimitive.int)

        assertEquals(listOf(EnergyRequest(LocalDate.of(2026, 11, 7), today, 60)), EnergyHistory.requests(EnergyRange.WEEK, today))
        // 30 days back from 13 Nov is 15 Oct: one quarter.
        assertEquals(listOf(EnergyRequest(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), 1440)), EnergyHistory.requests(EnergyRange.MONTH, today))
        // From 10 Oct, 30 days back crosses into the previous quarter.
        assertEquals(
            listOf(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)),
            EnergyHistory.requests(EnergyRange.MONTH, LocalDate.of(2026, 10, 10)).map { it.start },
        )
        assertEquals(listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)), EnergyHistory.requests(EnergyRange.YEAR, today).map { it.start })
        assertEquals(listOf(LocalDate.of(2026, 1, 1)), EnergyHistory.requests(EnergyRange.YEAR, LocalDate.of(2026, 12, 31)).map { it.start })
        assertTrue(EnergyHistory.requests(EnergyRange.YEAR, today).all { it.interval == 43200 })
    }

    @Test
    fun todayByTheHour() {
        val req = EnergyHistory.requests(EnergyRange.TODAY, today).single()
        val s = EnergyHistory.assemble(EnergyRange.TODAY, today, listOf(req to EnergyHistory.parseData(j(hourly))))
        assertEquals(24, s.bars.size)
        assertEquals(today.atStartOfDay(), s.bars[0].start)
        assertEquals(12.0, s.bars[0].wh)
        assertEquals(80.0, s.peak!!.wh)
        assertEquals(7, s.peak!!.start.hour)
        assertEquals(482.0, s.totalWh, 0.0)
        assertEquals(LocalDateTime.of(2026, 11, 13, 14, 25, 31), EnergyHistory.localTime(j(hourly)))
    }

    @Test
    fun weekSumsTheHoursOfEachDay() {
        val req = EnergyHistory.requests(EnergyRange.WEEK, today).single()
        // 7 days × 24 hours of 2 Wh, the last day only partly recorded.
        val data = List(6 * 24) { 2.0 } + List(10) { 1.0 }
        val s = EnergyHistory.assemble(EnergyRange.WEEK, today, listOf(req to data))
        assertEquals(7, s.bars.size)
        assertEquals(LocalDate.of(2026, 11, 7).atStartOfDay(), s.bars.first().start)
        assertEquals(48.0, s.bars.first().wh)
        assertEquals(10.0, s.bars.last().wh)
    }

    @Test
    fun thirtyDaysFromTheQuarter() {
        val req = EnergyHistory.requests(EnergyRange.MONTH, today).single()
        val s = EnergyHistory.assemble(EnergyRange.MONTH, today, listOf(req to EnergyHistory.parseData(j(daily))))
        assertEquals(30, s.bars.size)
        assertEquals(LocalDate.of(2026, 10, 15).atStartOfDay(), s.bars.first().start)
        assertEquals(395.0, s.bars.first().wh) // 15 October is the 15th value
        assertEquals(250.0, s.bars.last().wh) // today, so far
    }

    @Test
    fun twelveMonthsAcrossTwoYears() {
        val reqs = EnergyHistory.requests(EnergyRange.YEAR, today)
        val s = EnergyHistory.assemble(
            EnergyRange.YEAR, today,
            listOf(reqs[0] to EnergyHistory.parseData(j(monthlyLastYear)), reqs[1] to EnergyHistory.parseData(j(monthlyThisYear))),
        )
        assertEquals(12, s.bars.size)
        assertEquals(LocalDate.of(2025, 12, 1).atStartOfDay(), s.bars.first().start)
        assertEquals(15500.0, s.bars.first().wh)
        assertEquals(LocalDate.of(2026, 11, 1).atStartOfDay(), s.bars.last().start)
        assertEquals(5300.0, s.bars.last().wh)
    }

    @Test
    fun missingAndBadValuesAreGaps() {
        assertEquals(listOf(1.0, null, null, 4.0), EnergyHistory.parseData(j("""{"data":[1,"x",-5,4]}""")))
        assertEquals(emptyList<Double?>(), EnergyHistory.parseData(j("""{"error":1}""")))
        assertEquals(emptyList<Double?>(), EnergyHistory.parseData(j("[]")))
        val s = EnergyHistory.assemble(EnergyRange.TODAY, today, emptyList())
        assertEquals(24, s.bars.size)
        assertTrue(s.bars.all { it.wh == null })
        assertFalse(s.hasData)
        assertNull(s.peak)
        assertNull(EnergyHistory.localTime(j("""{"local_time":"soon"}""")))
    }

    @Test
    fun oversizedAnswersAreCapped() {
        val big = "{\"data\":[" + List(20_000) { "1" }.joinToString(",") + "]}"
        assertEquals(EnergyHistory.MAX_VALUES, EnergyHistory.parseData(j(big)).size)
    }

    @Test
    fun legacyHistoryLists() {
        val u = j(legacyUsage)
        val day = EnergyHistory.fromUsage(EnergyRange.TODAY, u, now)!!
        assertEquals(24, day.bars.size)
        assertEquals(24.0, day.bars[14].wh) // the current hour is the last value
        assertEquals(10.0, day.bars[0].wh)
        assertNull(day.bars[15].wh) // later today: nothing yet
        val week = EnergyHistory.fromUsage(EnergyRange.WEEK, u, now)!!
        assertEquals(listOf(24.0, 48.0, 0.0, 0.0, 0.0, 0.0, 20.0), week.bars.map { it.wh })
        assertEquals(today.atStartOfDay(), week.bars.last().start)
        val month = EnergyHistory.fromUsage(EnergyRange.MONTH, u, now)!!
        assertEquals(329.0, month.bars.last().wh)
        assertEquals(300.0, month.bars.first().wh)
        val year = EnergyHistory.fromUsage(EnergyRange.YEAR, u, now)!!
        assertEquals(1200.0, year.bars.last().wh)
        assertEquals(LocalDate.of(2026, 11, 1).atStartOfDay(), year.bars.last().start)
        // Newer firmware: no lists in get_energy_usage.
        assertNull(EnergyHistory.fromUsage(EnergyRange.YEAR, j("""{"today_energy":1}"""), now))
    }

    /** A plug that answers reads only; [hasEnergyData] false is an older firmware without get_energy_data. */
    private class FakePlug(val hasEnergyData: Boolean, val answers: Map<Int, String>, val usage: String) : TapoCaller {
        val sent = mutableListOf<Pair<String, JsonObject?>>()
        override suspend fun call(method: String, params: JsonObject?): JsonElement {
            sent += method to params
            check(TapoMethods.isRead(method)) { "wrote $method" }
            return when (method) {
                TapoMethods.GET_ENERGY_DATA -> if (!hasEnergyData) throw TapoException.DeviceError(-1) else
                    Json.parseToJsonElement(answers.getValue(params!!["interval"]!!.jsonPrimitive.int))
                TapoMethods.GET_ENERGY_USAGE -> Json.parseToJsonElement(usage)
                else -> error("unexpected $method")
            }
        }
    }

    @Test
    fun readsTheGraphsAndFallsBackOnOlderFirmware() = runBlocking {
        val plug = FakePlug(true, mapOf(60 to hourly, 1440 to daily, 43200 to monthlyThisYear), "{}")
        val s = EnergyHistory.read(plug, EnergyRange.TODAY, now)
        assertEquals(482.0, s.totalWh, 0.0)
        assertEquals(listOf(TapoMethods.GET_ENERGY_DATA), plug.sent.map { it.first })

        val old = FakePlug(false, emptyMap(), legacyUsage)
        val y = EnergyHistory.read(old, EnergyRange.YEAR, now)
        assertEquals(7800.0, y.totalWh, 0.0)
        assertEquals(listOf(TapoMethods.GET_ENERGY_DATA, TapoMethods.GET_ENERGY_USAGE), old.sent.map { it.first })
    }
}
