package app.aodkit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.aodkit.CLOCK_SCALE_RANGE
import app.aodkit.ClockColor
import app.aodkit.ClockFont
import app.aodkit.ClockSettings
import app.aodkit.ClockStyle
import app.aodkit.ClockWeight
import app.aodkit.DialNumerals
import app.aodkit.DialTicks
import app.aodkit.HandStyle
import app.aodkit.HourFormat
import app.aodkit.LeadingZero
import app.aodkit.TimeSystem
import app.aodkit.clockZone
import app.aodkit.is24Hour
import app.aodkit.utcOffsetLabel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

private const val BATTERY_NOTE = "Updates every second; uses more battery."

/** One line for the main Settings page, e.g. "Digital · 24-hour · Device time zone". */
internal fun clockSummary(clock: ClockSettings, system24h: Boolean): String {
    val style = when (clock.style) {
        ClockStyle.DIGITAL -> "Digital"
        ClockStyle.ANALOG -> "Analog"
        ClockStyle.BOTH -> "Analog and digital"
    }
    val format = if (clock.style == ClockStyle.ANALOG || clock.system == TimeSystem.STANDARD) {
        if (is24Hour(clock, system24h)) "24-hour" else "12-hour"
    } else {
        clock.system.title
    }
    return "$style · $format · ${zoneTitle(clock.zone)}"
}

private val TimeSystem.title
    get() = when (this) {
        TimeSystem.STANDARD -> "Standard"
        TimeSystem.MILITARY -> "Military"
        TimeSystem.SWATCH_BEATS -> "Swatch Internet Time"
        TimeSystem.DECIMAL -> "Decimal time"
        TimeSystem.UNIX -> "Unix time"
    }

private fun zoneTitle(id: String) = if (id.isEmpty()) "Device time zone" else id.replace('_', ' ')

/** "Asia/Tokyo · UTC+9"; just "UTC" when the name already is the offset. */
private fun zoneWithOffset(id: String): String {
    val title = zoneTitle(id)
    val offset = utcOffsetLabel(clockZone(id), System.currentTimeMillis())
    return if (title == offset) title else "$title · $offset"
}

/**
 * The Clock page. [update] applies a change to the stored clock settings; [onPickZone] opens the zone
 * picker for the main clock (false) or the second clock (true).
 */
internal fun LazyListScope.clockSettingsItems(
    clock: ClockSettings,
    system24h: Boolean,
    update: ((ClockSettings) -> ClockSettings) -> Unit,
    onPickZone: (secondClock: Boolean) -> Unit,
) {
    val digital = clock.style != ClockStyle.ANALOG
    val standardDigital = digital && clock.system == TimeSystem.STANDARD

    item { ClockPreview(clock) }

    item { SectionHeader("Style") }
    radioItems(
        ClockStyle.entries,
        clock.style,
        label = {
            when (it) {
                ClockStyle.DIGITAL -> "Digital"
                ClockStyle.ANALOG -> "Analog"
                ClockStyle.BOTH -> "Analog and digital"
            }
        },
    ) { v -> update { it.copy(style = v) } }

    if (digital) {
        item { SectionHeader("Time system") }
        radioItems(
            TimeSystem.entries,
            clock.system,
            label = { it.title },
            description = {
                when (it) {
                    TimeSystem.STANDARD -> "Hours and minutes, e.g. 14:30."
                    TimeSystem.MILITARY -> "24-hour without a colon, e.g. 1430."
                    TimeSystem.SWATCH_BEATS -> "1000 .beats a day on Biel Mean Time, the same everywhere, e.g. @645. Ignores the time zone."
                    TimeSystem.DECIMAL -> "French Revolutionary time: 10 hours of 100 minutes a day, e.g. 6:04."
                    TimeSystem.UNIX -> "Seconds since 1 January 1970, e.g. 1795782600. $BATTERY_NOTE"
                }
            },
        ) { v -> update { it.copy(system = v) } }
    }

    item { SectionHeader("Format") }
    // The hour format also shapes the second clock, which always uses standard time.
    if (standardDigital || clock.secondClock) {
        item { SubHeader("Hours") }
        radioItems(
            HourFormat.entries,
            clock.hourFormat,
            label = {
                when (it) {
                    HourFormat.SYSTEM -> "Phone setting (${if (system24h) "24-hour" else "12-hour"})"
                    HourFormat.H12 -> "12-hour"
                    HourFormat.H24 -> "24-hour"
                }
            },
        ) { v -> update { it.copy(hourFormat = v) } }
        if (!is24Hour(clock, system24h)) {
            item {
                SwitchRow("Show AM/PM", "After the time, in small letters.", clock.showAmPm) { v -> update { it.copy(showAmPm = v) } }
            }
        }
        item { SubHeader("Leading zero") }
        radioItems(
            LeadingZero.entries,
            clock.leadingZero,
            label = {
                when (it) {
                    LeadingZero.AUTO -> "Automatic: 09:05 in 24-hour, 9:05 in 12-hour"
                    LeadingZero.ALWAYS -> "Always: 09:05"
                    LeadingZero.NEVER -> "Never: 9:05"
                }
            },
        ) { v -> update { it.copy(leadingZero = v) } }
    }
    // Unix time always counts seconds; only an analog dial next to it can still use a second hand.
    if (!(clock.style == ClockStyle.DIGITAL && clock.system == TimeSystem.UNIX)) {
        item {
            SwitchRow(
                "Show seconds",
                if (clock.style == ClockStyle.DIGITAL) BATTERY_NOTE else "Adds a second hand. $BATTERY_NOTE",
                clock.showSeconds,
            ) { v -> update { it.copy(showSeconds = v) } }
        }
    }

    item { SectionHeader("Time zone") }
    item {
        SettingRow(
            "Time zone",
            zoneWithOffset(clock.zone) + ". The date follows it too.",
        ) { onPickZone(false) }
    }
    item {
        SwitchRow("Show time zone", "City and UTC offset under the clock.", clock.showZoneLabel) { v ->
            update { it.copy(showZoneLabel = v) }
        }
    }

    item { SectionHeader("Second clock") }
    item {
        SwitchRow("Show a second clock", "A small line with the time in another time zone.", clock.secondClock) { v ->
            update { it.copy(secondClock = v) }
        }
    }
    if (clock.secondClock) {
        item {
            SettingRow("Second clock time zone", zoneWithOffset(clock.secondZone)) {
                onPickZone(true)
            }
        }
    }

    item { SectionHeader("Appearance") }
    item { SubHeader("Size") }
    // 10 % steps; rounded, so 100 % is exactly the default size.
    item { PercentSlider(clock.scale, CLOCK_SCALE_RANGE, steps = 9) { v -> update { it.copy(scale = (v * 10).roundToInt() / 10f) } } }
    item { SubHeader("Weight") }
    if (clock.font != ClockFont.SANS) {
        item {
            Text(
                "This font usually has only regular and bold; lighter weights look regular.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    radioItems(
        ClockWeight.entries,
        clock.weight,
        label = {
            when (it) {
                ClockWeight.THIN -> "Thin"
                ClockWeight.LIGHT -> "Light"
                ClockWeight.REGULAR -> "Regular"
                ClockWeight.MEDIUM -> "Medium"
                ClockWeight.BOLD -> "Bold"
            }
        },
    ) { v -> update { it.copy(weight = v) } }
    item { SubHeader("Font") }
    radioItems(
        ClockFont.entries,
        clock.font,
        label = {
            when (it) {
                ClockFont.SANS -> "Sans serif"
                ClockFont.SERIF -> "Serif"
                ClockFont.MONO -> "Monospace"
                ClockFont.CURSIVE -> "Handwriting"
            }
        },
        font = { it.family },
    ) { v -> update { it.copy(font = v) } }
    item { SubHeader("Colour") }
    item {
        ColorSwatches(clock.color) { v -> update { it.copy(color = v) } }
    }

    if (clock.style != ClockStyle.DIGITAL) {
        item { SectionHeader("Analog dial") }
        item { SubHeader("Marks") }
        radioItems(
            DialTicks.entries,
            clock.ticks,
            label = {
                when (it) {
                    DialTicks.NONE -> "None"
                    DialTicks.HOURS -> "12 hour marks"
                    DialTicks.MINUTES -> "60 minute marks"
                }
            },
        ) { v -> update { it.copy(ticks = v) } }
        item { SubHeader("Numbers") }
        radioItems(
            DialNumerals.entries,
            clock.numerals,
            label = {
                when (it) {
                    DialNumerals.NONE -> "None"
                    DialNumerals.ARABIC -> "Arabic: 1 to 12"
                    DialNumerals.ROMAN -> "Roman: I to XII"
                }
            },
        ) { v -> update { it.copy(numerals = v) } }
        item { SubHeader("Hands") }
        radioItems(
            HandStyle.entries,
            clock.hands,
            label = {
                when (it) {
                    HandStyle.LINE -> "Line"
                    HandStyle.BATON -> "Baton: straight bars"
                    HandStyle.DAUPHINE -> "Dauphine: tapered"
                }
            },
        ) { v -> update { it.copy(hands = v) } }
        item {
            SwitchRow("Outer ring", "A circle around the dial.", clock.dialRing) { v -> update { it.copy(dialRing = v) } }
        }
    }
}

/** The clock as the AOD Screen draws it, ticking, so changes show at once. */
@Composable
private fun ClockPreview(clock: ClockSettings) {
    Box(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .padding(vertical = 24.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { AodClock(clock) }
}

private fun <T> LazyListScope.radioItems(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: ((T) -> String)? = null,
    font: ((T) -> FontFamily?)? = null,
    onSelect: (T) -> Unit,
) {
    items(options) { option ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(option == selected) { onSelect(option) }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == selected, onClick = null)
            Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
                Text(label(option), fontFamily = font?.invoke(option))
                if (description != null) {
                    Text(description(option), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, trailing = { Switch(checked = checked, onCheckedChange = onChange) })
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColorSwatches(selected: ClockColor, onSelect: (ClockColor) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ClockColor.entries.forEach { color ->
            Box(
                Modifier
                    .size(40.dp)
                    .then(
                        if (color == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier,
                    )
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(Color(color.argb))
                    .clickable { onSelect(color) },
            )
        }
    }
}

/** Every zone the device knows, sorted by current UTC offset, with a search box. */
@Composable
internal fun ZonePickerDialog(
    title: String,
    selected: String,
    includeDevice: Boolean,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val zones = remember {
        val instant = Instant.ofEpochMilli(now)
        ZoneId.getAvailableZoneIds()
            .map { ZoneId.of(it) }
            .sortedWith(compareBy({ it.rules.getOffset(instant).totalSeconds }, { it.id }))
    }
    val query = rememberTextFieldState()
    val needle = query.text.toString().trim().replace(' ', '_')
    val shown = remember(needle) { zones.filter { it.id.contains(needle, ignoreCase = true) } }
    val pick = { id: String ->
        onPick(id)
        onClose()
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                // State-based field without keyboard suggestions: with a plain String value, Samsung
                // Keyboard's predictive text kept re-inserting a suggestion in a loop.
                OutlinedTextField(
                    state = query,
                    placeholder = { Text("Search city or region") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    if (includeDevice && needle.isEmpty()) {
                        item { ZoneRow("Device time zone", ZoneId.systemDefault().id.replace('_', ' '), selected.isEmpty()) { pick("") } }
                    }
                    items(shown, key = { it.id }) { zone ->
                        ZoneRow(zone.id.replace('_', ' '), utcOffsetLabel(zone, now), zone.id == selected) { pick(zone.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun ZoneRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
