package app.aodkit.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aodkit.ClockFont
import app.aodkit.ClockSettings
import app.aodkit.ClockStyle
import app.aodkit.ClockText
import app.aodkit.ClockWeight
import app.aodkit.DialNumerals
import app.aodkit.DialTicks
import app.aodkit.HandStyle
import app.aodkit.TimeSystem
import app.aodkit.clockZone
import app.aodkit.formatClock
import app.aodkit.msUntilNextTick
import app.aodkit.zoneCity
import app.aodkit.zoneLabel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

private val DIGITAL_SIZE = 96.sp
private val DIAL_SIZE = 220.dp

/** In "Analog and digital", the readout under the dial is this fraction of the digital clock's size. */
private const val READOUT_FRACTION = 0.4f

/** Seconds, AM/PM and centibeats are drawn at this fraction of the main digits. */
private val SUFFIX_SIZE = 0.4.em

private val ROMAN = listOf("XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI")

internal val ClockFont.family: FontFamily?
    get() = when (this) {
        ClockFont.SANS -> null
        ClockFont.SERIF -> FontFamily.Serif
        ClockFont.MONO -> FontFamily.Monospace
        ClockFont.CURSIVE -> FontFamily.Cursive
    }

internal val ClockWeight.fontWeight: FontWeight
    get() = when (this) {
        ClockWeight.THIN -> FontWeight.Thin
        ClockWeight.LIGHT -> FontWeight.Light
        ClockWeight.REGULAR -> FontWeight.Normal
        ClockWeight.MEDIUM -> FontWeight.Medium
        ClockWeight.BOLD -> FontWeight.Bold
    }

/** Stroke width of the analog dial's lines; hands are multiples of it. */
private val ClockWeight.stroke: Dp
    get() = when (this) {
        ClockWeight.THIN -> 1.dp
        ClockWeight.LIGHT -> 1.5.dp
        ClockWeight.REGULAR -> 2.dp
        ClockWeight.MEDIUM -> 3.dp
        ClockWeight.BOLD -> 4.dp
    }

/** The AOD Screen clock as configured in [clock]; it ticks by itself, every second only when it needs to. */
@Composable
fun AodClock(clock: ClockSettings) {
    val zone = remember(clock.zone) { clockZone(clock.zone) }
    val now = rememberClockTicker(clock, zone)
    val system24h = DateFormat.is24HourFormat(LocalContext.current)
    val color = Color(clock.color.argb)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (clock.style != ClockStyle.DIGITAL) AnalogDial(now, zone, clock, color)
        if (clock.style != ClockStyle.ANALOG) {
            if (clock.style == ClockStyle.BOTH) Spacer(Modifier.height(12.dp))
            val fraction = if (clock.style == ClockStyle.BOTH) READOUT_FRACTION else 1f
            DigitalTime(formatClock(now, zone, clock, system24h), clock, color, DIGITAL_SIZE * clock.scale * fraction)
        }
        if (clock.showZoneLabel) SmallClockLine(zoneLabel(zone, now), clock, color)
        if (clock.secondClock) {
            val secondZone = remember(clock.secondZone) { clockZone(clock.secondZone) }
            val time = formatClock(now, secondZone, clock.copy(system = TimeSystem.STANDARD, showSeconds = false), system24h)
            SmallClockLine("${zoneCity(secondZone)}   ${time.main}${time.suffix}", clock, color)
        }
    }
}

@Composable
private fun rememberClockTicker(clock: ClockSettings, zone: ZoneId): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(clock, zone) {
        now = System.currentTimeMillis()
        while (true) {
            delay(msUntilNextTick(System.currentTimeMillis(), zone, clock))
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Shrinks to fit the width, so long readouts (Unix time, seconds, a large size) never overflow. */
@Composable
private fun DigitalTime(text: ClockText, clock: ClockSettings, color: Color, maxSize: TextUnit) {
    Text(
        text = buildAnnotatedString {
            append(text.main)
            if (text.suffix.isNotEmpty()) withStyle(SpanStyle(fontSize = SUFFIX_SIZE)) { append(text.suffix) }
        },
        color = color,
        fontSize = maxSize,
        fontFamily = clock.font.family,
        fontWeight = clock.weight.fontWeight,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = maxSize, stepSize = 2.sp),
    )
}

@Composable
private fun SmallClockLine(text: String, clock: ClockSettings, color: Color) {
    Spacer(Modifier.height(8.dp))
    Text(text, color = color, fontSize = 16.sp, fontFamily = clock.font.family, fontWeight = FontWeight.Light, maxLines = 1)
}

/** A standard 12-hour dial in [zone], whatever time system the digital readout uses. */
@Composable
private fun AnalogDial(now: Long, zone: ZoneId, clock: ClockSettings, color: Color) {
    // Never wider than the screen, nor so tall that the rest of the content is pushed onto the sensor.
    val window = LocalWindowInfo.current.containerSize
    val size = with(LocalDensity.current) {
        minOf(DIAL_SIZE * clock.scale, window.width.toDp() - 48.dp, window.height.toDp() * 0.3f)
    }
    val measurer = rememberTextMeasurer()
    val time = Instant.ofEpochMilli(now).atZone(zone)
    val minutes = time.minute + time.second / 60f
    val hours = time.hour % 12 + minutes / 60f
    Canvas(Modifier.size(size)) {
        val radius = this.size.minDimension / 2
        val w = clock.weight.stroke.toPx()
        if (clock.dialRing) drawCircle(color, radius - w / 2, style = Stroke(w))

        val count = when (clock.ticks) {
            DialTicks.NONE -> 0
            DialTicks.HOURS -> 12
            DialTicks.MINUTES -> 60
        }
        val tickOuter = radius - w * 3
        for (i in 0 until count) {
            val major = count == 12 || i % 5 == 0
            rotate(i * 360f / count) {
                drawLine(
                    color,
                    Offset(center.x, center.y - tickOuter),
                    Offset(center.x, center.y - tickOuter + radius * (if (major) 0.08f else 0.04f)),
                    strokeWidth = if (major) w * 1.5f else w * 0.75f,
                    cap = StrokeCap.Round,
                )
            }
        }

        if (clock.numerals != DialNumerals.NONE) {
            val style = TextStyle(
                color = color,
                fontSize = (radius * 0.16f).toSp(),
                fontFamily = clock.font.family,
                fontWeight = clock.weight.fontWeight,
            )
            val numeralRadius = radius * (if (count > 0) 0.7f else 0.82f)
            for (i in 0 until 12) {
                val label = if (clock.numerals == DialNumerals.ROMAN) ROMAN[i] else "${if (i == 0) 12 else i}"
                val layout = measurer.measure(label, style)
                val angle = Math.toRadians(i * 30.0)
                val x = center.x + numeralRadius * Math.sin(angle).toFloat()
                val y = center.y - numeralRadius * Math.cos(angle).toFloat()
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
            }
        }

        hand(hours * 30f, radius * 0.5f, w * 3f, clock.hands, color)
        hand(minutes * 6f, radius * 0.75f, w * 2f, clock.hands, color)
        if (clock.showSeconds) hand(time.second * 6f, radius * 0.85f, w * 0.75f, HandStyle.LINE, color)
        drawCircle(color, w * 2.5f)
    }
}

private fun DrawScope.hand(degrees: Float, length: Float, width: Float, style: HandStyle, color: Color) {
    rotate(degrees) {
        val (cx, cy) = center
        when (style) {
            HandStyle.LINE -> drawLine(color, center, Offset(cx, cy - length), width, StrokeCap.Round)
            HandStyle.BATON -> drawRect(color, Offset(cx - width / 2, cy - length), Size(width, length + width * 2))
            HandStyle.DAUPHINE -> drawPath(
                Path().apply {
                    moveTo(cx, cy - length)
                    lineTo(cx + width, cy - length * 0.2f)
                    lineTo(cx, cy + width * 2)
                    lineTo(cx - width, cy - length * 0.2f)
                    close()
                },
                color,
            )
        }
    }
}
