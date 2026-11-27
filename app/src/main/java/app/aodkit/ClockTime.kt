package app.aodkit

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L
private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/** A decimal second and a .beat's hundredth are both 1/100000 of a day. */
private const val DAY_HUNDREDTHOUSANDTH_MS = 864L
private const val DAY_THOUSANDTH_MS = 86_400L

/** Biel Mean Time, the base of Swatch Internet Time. */
private const val BMT_OFFSET_MS = HOUR_MS

/** The clock's time as a main part and a smaller [suffix] (seconds, AM/PM, centibeats). */
data class ClockText(val main: String, val suffix: String = "")

/** The zone [id] names; empty or unknown (e.g. dropped from the device's tz database) means the device's zone. */
fun clockZone(id: String): ZoneId {
    if (id.isEmpty()) return ZoneId.systemDefault()
    return try {
        ZoneId.of(id)
    } catch (_: DateTimeException) {
        ZoneId.systemDefault()
    }
}

fun is24Hour(clock: ClockSettings, system24h: Boolean) = when (clock.hourFormat) {
    HourFormat.SYSTEM -> system24h
    HourFormat.H12 -> false
    HourFormat.H24 -> true
}

fun formatClock(epochMs: Long, zone: ZoneId, clock: ClockSettings, system24h: Boolean): ClockText {
    val time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
    val seconds = clock.showSeconds
    return when (clock.system) {
        TimeSystem.STANDARD -> {
            val h24 = is24Hour(clock, system24h)
            val hour = if (h24) time.hour else (time.hour % 12).let { if (it == 0) 12 else it }
            val pad = when (clock.leadingZero) {
                LeadingZero.AUTO -> h24
                LeadingZero.ALWAYS -> true
                LeadingZero.NEVER -> false
            }
            ClockText(
                (if (pad) two(hour) else "$hour") + ":" + two(time.minute),
                (if (seconds) ":" + two(time.second) else "") +
                    (if (!h24 && clock.showAmPm) (if (time.hour < 12) " AM" else " PM") else ""),
            )
        }
        TimeSystem.MILITARY -> ClockText(two(time.hour) + two(time.minute), if (seconds) two(time.second) else "")
        TimeSystem.SWATCH_BEATS -> {
            val centibeats = Math.floorMod(epochMs + BMT_OFFSET_MS, DAY_MS) / DAY_HUNDREDTHOUSANDTH_MS
            ClockText("@" + (centibeats / 100).toString().padStart(3, '0'), if (seconds) "." + two((centibeats % 100).toInt()) else "")
        }
        TimeSystem.DECIMAL -> {
            // From the wall-clock time of day, so a DST day still runs 0:00 to 9:99.
            val decimalSeconds = time.toNanoOfDay() / (DAY_HUNDREDTHOUSANDTH_MS * 1_000_000)
            ClockText(
                "${decimalSeconds / 10_000}:" + two((decimalSeconds / 100 % 100).toInt()),
                if (seconds) ":" + two((decimalSeconds % 100).toInt()) else "",
            )
        }
        TimeSystem.UNIX -> ClockText(Math.floorDiv(epochMs, SECOND_MS).toString())
    }
}

/** Milliseconds until the clock next shows something different; never more than a minute. */
fun msUntilNextTick(epochMs: Long, zone: ZoneId, clock: ClockSettings): Long {
    fun until(sinceAnchor: Long, unit: Long) = unit - Math.floorMod(sinceAnchor, unit)

    var next = until(epochMs, MINUTE_MS)
    if (clock.showSeconds || clock.system == TimeSystem.UNIX) next = minOf(next, until(epochMs, SECOND_MS))
    if (clock.style != ClockStyle.ANALOG) {
        val unit = if (clock.showSeconds) DAY_HUNDREDTHOUSANDTH_MS else DAY_THOUSANDTH_MS
        when (clock.system) {
            TimeSystem.DECIMAL -> {
                val msOfDay = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().toNanoOfDay() / 1_000_000
                next = minOf(next, until(msOfDay, unit))
            }
            TimeSystem.SWATCH_BEATS -> next = minOf(next, until(epochMs + BMT_OFFSET_MS, unit))
            else -> Unit
        }
    }
    return next
}

/** "UTC", "UTC+9", "UTC+5:30", "UTC-3". */
fun utcOffsetLabel(zone: ZoneId, epochMs: Long): String {
    val total = zone.rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds
    if (total == 0) return "UTC"
    val sign = if (total < 0) "-" else "+"
    val minutes = Math.abs(total) / 60
    return "UTC$sign${minutes / 60}" + if (minutes % 60 != 0) ":" + two(minutes % 60) else ""
}

/** The last part of the zone id: "TOKYO", "NEW YORK", "UTC". */
fun zoneCity(zone: ZoneId) = zone.id.substringAfterLast('/').replace('_', ' ').uppercase()

/** City and offset: "TOKYO · UTC+9"; just "UTC" for UTC itself. */
fun zoneLabel(zone: ZoneId, epochMs: Long): String {
    val city = zoneCity(zone)
    val offset = utcOffsetLabel(zone, epochMs)
    return if (city == offset) city else "$city · $offset"
}

private fun two(n: Int) = n.toString().padStart(2, '0')
