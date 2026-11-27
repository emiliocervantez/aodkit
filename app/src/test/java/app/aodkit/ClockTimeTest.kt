package app.aodkit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ClockTimeTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun format(iso: String, clock: ClockSettings, zone: ZoneId = utc, system24h: Boolean = true) =
        formatClock(at(iso), zone, clock, system24h)

    @Test
    fun `standard follows the system hour format with conventional padding`() {
        assertEquals(ClockText("09:05"), format("2026-11-27T09:05:07Z", ClockSettings(), system24h = true))
        assertEquals(ClockText("9:05"), format("2026-11-27T09:05:07Z", ClockSettings(), system24h = false))
    }

    @Test
    fun `standard 12-hour with seconds, AM-PM and explicit padding`() {
        val clock = ClockSettings(hourFormat = HourFormat.H12, showAmPm = true, showSeconds = true)
        assertEquals(ClockText("9:05", ":07 AM"), format("2026-11-27T09:05:07Z", clock))
        assertEquals(ClockText("9:05", ":07 PM"), format("2026-11-27T21:05:07Z", clock))
        assertEquals(ClockText("12:30", ":00 AM"), format("2026-11-27T00:30:00Z", clock))
        assertEquals(
            ClockText("09:05", ":07 AM"),
            format("2026-11-27T09:05:07Z", clock.copy(leadingZero = LeadingZero.ALWAYS)),
        )
        assertEquals(
            ClockText("9:05"),
            format("2026-11-27T09:05:07Z", ClockSettings(hourFormat = HourFormat.H24, leadingZero = LeadingZero.NEVER)),
        )
    }

    @Test
    fun `military is always padded and colon-free`() {
        assertEquals(ClockText("0905", "07"), format("2026-11-27T09:05:07Z", ClockSettings(system = TimeSystem.MILITARY, showSeconds = true)))
    }

    @Test
    fun `swatch beats use Biel Mean Time and ignore the zone`() {
        val clock = ClockSettings(system = TimeSystem.SWATCH_BEATS, showSeconds = true)
        assertEquals(ClockText("@541", ".66"), format("2026-11-27T12:00:00Z", clock))
        assertEquals(ClockText("@541", ".66"), format("2026-11-27T12:00:00Z", clock, zone = ZoneId.of("Asia/Tokyo")))
        assertEquals(ClockText("@000"), format("2026-11-26T23:00:00Z", ClockSettings(system = TimeSystem.SWATCH_BEATS)))
    }

    @Test
    fun `decimal time runs from local midnight, also on a DST day`() {
        val clock = ClockSettings(system = TimeSystem.DECIMAL)
        assertEquals(ClockText("0:00"), format("2026-11-27T00:00:00Z", clock))
        assertEquals(ClockText("5:00"), format("2026-11-27T12:00:00Z", clock))
        assertEquals(ClockText("7:50", ":00"), format("2026-11-27T18:00:00Z", clock.copy(showSeconds = true)))
        // Tallinn springs forward at 03:00 on 2026-03-29; local noon is 09:00 UTC that day.
        assertEquals(ClockText("5:00"), format("2026-03-29T09:00:00Z", clock, zone = ZoneId.of("Europe/Tallinn")))
    }

    @Test
    fun `unix time is epoch seconds`() {
        assertEquals(ClockText("1000000000"), format("2001-09-09T01:46:40.500Z", ClockSettings(system = TimeSystem.UNIX)))
    }

    @Test
    fun `next tick is the nearest visible change`() {
        assertEquals(29_750L, msUntilNextTick(at("2026-11-27T12:00:30.250Z"), utc, ClockSettings()))
        assertEquals(750L, msUntilNextTick(at("2026-11-27T12:00:30.250Z"), utc, ClockSettings(showSeconds = true)))
        assertEquals(750L, msUntilNextTick(at("2026-11-27T12:00:30.250Z"), utc, ClockSettings(system = TimeSystem.UNIX)))
        // A decimal minute (86.4 s) after midnight comes before the next standard minute.
        assertEquals(400L, msUntilNextTick(at("2026-11-27T00:01:26Z"), utc, ClockSettings(system = TimeSystem.DECIMAL)))
        // BMT midnight is 23:00 UTC; the first .beat ends 86.4 s later.
        assertEquals(400L, msUntilNextTick(at("2026-11-26T23:01:26Z"), utc, ClockSettings(system = TimeSystem.SWATCH_BEATS)))
        // The analog dial shows standard time only, so the decimal readout doesn't drive it.
        assertEquals(34_000L, msUntilNextTick(at("2026-11-27T00:01:26Z"), utc, ClockSettings(style = ClockStyle.ANALOG, system = TimeSystem.DECIMAL)))
    }

    @Test
    fun `zone ids and labels`() {
        assertEquals(ZoneId.systemDefault(), clockZone(""))
        assertEquals(ZoneId.systemDefault(), clockZone("Mars/Olympus_Mons"))
        val now = at("2026-11-27T12:00:00Z")
        assertEquals("TOKYO · UTC+9", zoneLabel(ZoneId.of("Asia/Tokyo"), now))
        assertEquals("KOLKATA · UTC+5:30", zoneLabel(ZoneId.of("Asia/Kolkata"), now))
        assertEquals("NEW YORK · UTC-5", zoneLabel(ZoneId.of("America/New_York"), now))
        assertEquals("UTC", zoneLabel(ZoneId.of("UTC"), now))
        assertEquals("NEW YORK", zoneCity(ZoneId.of("America/New_York")))
    }
}
