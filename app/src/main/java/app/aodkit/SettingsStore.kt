package app.aodkit

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val MAX_SHORTCUTS = 10

/** What a power-button press does while the AOD Screen is showing. */
enum class PowerButtonAction {
    DISPLAY_OFF,
    LOCK_SCREEN,

    /** Apps cannot block the power key, so the display is turned straight back on instead. */
    KEEP_ON,
}

/** What the ✕ button in the Notification Modal does. */
enum class ModalCloseAction {
    CLOSE,

    /** Dismiss every notification in the modal (all of that app's), then close it. */
    DISMISS_ALL,
}

/** How Shortcuts fan out from the Shortcut Launcher button. */
enum class ShortcutLayout { VERTICAL, HORIZONTAL }

/** How the user starts unlocking from the AOD Screen. */
enum class UnlockMethod {
    /** Touching the ring over the sensor closes the AOD Screen so the Lock Screen's sensor takes over. */
    FINGERPRINT,

    /** Swiping up closes the AOD Screen; the Lock Screen appears with its fingerprint sensor on. */
    SWIPE_LOCK_SCREEN,

    /** Swiping up asks Android to unlock, which brings up the PIN/pattern screen. */
    SWIPE_PIN,
}

/** How the clock on the AOD Screen is drawn. */
enum class ClockStyle { DIGITAL, ANALOG, BOTH }

/** How the digital clock counts time. The analog dial always shows standard time. */
enum class TimeSystem {
    STANDARD,

    /** 24-hour without a colon: 1430. */
    MILITARY,

    /** Swatch Internet Time: 1000 .beats per day on Biel Mean Time (UTC+1), the same everywhere. */
    SWATCH_BEATS,

    /** French Revolutionary time: 10 hours of 100 minutes of 100 seconds per day. */
    DECIMAL,

    /** Seconds since 1970-01-01 UTC. */
    UNIX,
}

enum class HourFormat { SYSTEM, H12, H24 }

/** AUTO follows convention: 24-hour pads the hour (09:05), 12-hour doesn't (9:05). */
enum class LeadingZero { AUTO, ALWAYS, NEVER }

enum class ClockWeight { THIN, LIGHT, REGULAR, MEDIUM, BOLD }

enum class ClockFont { SANS, SERIF, MONO, CURSIVE }

/** Muted colours, dim enough for a black always-on screen. */
enum class ClockColor(val argb: Long) {
    GREY(0xFF8E8E8E),
    WHITE(0xFFE0E0E0),
    RED(0xFFC0645F),
    ORANGE(0xFFC98552),
    AMBER(0xFFC9A752),
    GREEN(0xFF74A86B),
    TEAL(0xFF5FA39E),
    BLUE(0xFF6489C0),
    PURPLE(0xFF9277BD),
    PINK(0xFFC2729D),
}

enum class DialTicks { NONE, HOURS, MINUTES }

enum class DialNumerals { NONE, ARABIC, ROMAN }

enum class HandStyle { LINE, BATON, DAUPHINE }

data class ClockSettings(
    val style: ClockStyle = ClockStyle.DIGITAL,
    val system: TimeSystem = TimeSystem.STANDARD,
    val hourFormat: HourFormat = HourFormat.SYSTEM,
    /** 12-hour only. */
    val showAmPm: Boolean = false,
    val leadingZero: LeadingZero = LeadingZero.AUTO,
    /** Digital seconds and the analog second hand; the clock then redraws every second. */
    val showSeconds: Boolean = false,
    /** IANA zone id; empty follows the device's zone. */
    val zone: String = "",
    val showZoneLabel: Boolean = false,
    /** A small extra clock for [secondZone] under the main one. */
    val secondClock: Boolean = false,
    val secondZone: String = "UTC",
    /** Clock size relative to the default, [CLOCK_SCALE_RANGE]. */
    val scale: Float = 1f,
    val weight: ClockWeight = ClockWeight.THIN,
    val font: ClockFont = ClockFont.SANS,
    val color: ClockColor = ClockColor.GREY,
    val ticks: DialTicks = DialTicks.HOURS,
    val numerals: DialNumerals = DialNumerals.NONE,
    val hands: HandStyle = HandStyle.LINE,
    val dialRing: Boolean = true,
)

val CLOCK_SCALE_RANGE = 0.5f..1.5f

data class AodSettings(
    val enabled: Boolean = false,
    /** Shortcut package names, in display order. */
    val shortcuts: List<String> = emptyList(),
    val shortcutLayout: ShortcutLayout = ShortcutLayout.VERTICAL,
    val hiddenApps: Set<String> = emptySet(),
    /** Show a count badge on a Notification Icon when its app has more than one notification. */
    val showNotificationCount: Boolean = true,
    /** Ignore "hide content" lock-screen settings on the AOD Screen (SECRET stays hidden). */
    val showHiddenContent: Boolean = false,
    val modalCloseAction: ModalCloseAction = ModalCloseAction.CLOSE,
    /** Close the Notification Modal after [modalAutoCloseSeconds] without a touch; off keeps it open. */
    val modalAutoClose: Boolean = true,
    val modalAutoCloseSeconds: Int = DEFAULT_AUTO_CLOSE_SECONDS,
    val powerButton: PowerButtonAction = PowerButtonAction.DISPLAY_OFF,
    /** Window brightness of the AOD Screen, 0..1. */
    val brightness: Float = DEFAULT_BRIGHTNESS,
    /** Burn-in protection: jump the content block to a random spot every minute instead of a small shift. */
    val moveContent: Boolean = false,
    /** Battery percentage under the date on the AOD Screen. */
    val showBattery: Boolean = true,
    val unlockMethod: UnlockMethod = UnlockMethod.FINGERPRINT,
    /** Vertical centre of the in-display fingerprint sensor, as a fraction of the screen height. */
    val sensorY: Float = DEFAULT_SENSOR_Y,
    /** Draw the ring over the sensor; when hidden, touching the spot still hands over to the fingerprint. */
    val showSensorRing: Boolean = true,
    val clock: ClockSettings = ClockSettings(),
)

const val DEFAULT_AUTO_CLOSE_SECONDS = 30
val AUTO_CLOSE_SECONDS_RANGE = 10..120

/** Galaxy A56: the sensor's touch rect is 427,1970–652,2195 on a 1080x2340 screen. */
const val DEFAULT_SENSOR_Y = 0.89f

/** Close to the Samsung system AOD's own level (0.19 on a Galaxy A56). */
const val DEFAULT_BRIGHTNESS = 0.2f
const val MIN_BRIGHTNESS = 0.01f

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<AodSettings> = store.data.map { p ->
        AodSettings(
            enabled = p[ENABLED] ?: false,
            shortcuts = p[SHORTCUTS]?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
            shortcutLayout = p[SHORTCUT_LAYOUT]
                ?.let { name -> ShortcutLayout.entries.firstOrNull { it.name == name } }
                ?: ShortcutLayout.VERTICAL,
            hiddenApps = p[HIDDEN_APPS].orEmpty(),
            showNotificationCount = p[SHOW_NOTIFICATION_COUNT] ?: true,
            showHiddenContent = p[SHOW_HIDDEN_CONTENT] ?: false,
            modalCloseAction = p[MODAL_CLOSE_ACTION]
                ?.let { name -> ModalCloseAction.entries.firstOrNull { it.name == name } }
                ?: ModalCloseAction.CLOSE,
            modalAutoClose = p[MODAL_AUTO_CLOSE] ?: true,
            modalAutoCloseSeconds = p[MODAL_AUTO_CLOSE_SECONDS] ?: DEFAULT_AUTO_CLOSE_SECONDS,
            powerButton = p[POWER_BUTTON]
                ?.let { name -> PowerButtonAction.entries.firstOrNull { it.name == name } }
                ?: PowerButtonAction.DISPLAY_OFF,
            brightness = p[BRIGHTNESS] ?: DEFAULT_BRIGHTNESS,
            moveContent = p[MOVE_CONTENT] ?: false,
            showBattery = p[SHOW_BATTERY] ?: true,
            unlockMethod = p[UNLOCK_METHOD]
                ?.let { name -> UnlockMethod.entries.firstOrNull { it.name == name } }
                ?: UnlockMethod.FINGERPRINT,
            sensorY = p[SENSOR_Y] ?: DEFAULT_SENSOR_Y,
            showSensorRing = p[SHOW_SENSOR_RING] ?: true,
            clock = readClock(p),
        )
    }

    /** Reads and writes inside one edit, so quick successive changes never overwrite each other. */
    suspend fun updateClock(transform: (ClockSettings) -> ClockSettings) {
        store.edit { p -> writeClock(p, transform(readClock(p))) }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    suspend fun setShortcuts(packages: List<String>) {
        store.edit { it[SHORTCUTS] = packages.take(MAX_SHORTCUTS).joinToString(",") }
    }

    suspend fun setShortcutLayout(layout: ShortcutLayout) {
        store.edit { it[SHORTCUT_LAYOUT] = layout.name }
    }

    suspend fun setHiddenApps(packages: Set<String>) {
        store.edit { it[HIDDEN_APPS] = packages }
    }

    suspend fun setShowNotificationCount(show: Boolean) {
        store.edit { it[SHOW_NOTIFICATION_COUNT] = show }
    }

    suspend fun setShowHiddenContent(show: Boolean) {
        store.edit { it[SHOW_HIDDEN_CONTENT] = show }
    }

    suspend fun setModalCloseAction(action: ModalCloseAction) {
        store.edit { it[MODAL_CLOSE_ACTION] = action.name }
    }

    suspend fun setModalAutoClose(enabled: Boolean) {
        store.edit { it[MODAL_AUTO_CLOSE] = enabled }
    }

    suspend fun setModalAutoCloseSeconds(seconds: Int) {
        store.edit { it[MODAL_AUTO_CLOSE_SECONDS] = seconds.coerceIn(AUTO_CLOSE_SECONDS_RANGE) }
    }

    suspend fun setPowerButton(action: PowerButtonAction) {
        store.edit { it[POWER_BUTTON] = action.name }
    }

    suspend fun setBrightness(brightness: Float) {
        store.edit { it[BRIGHTNESS] = brightness.coerceIn(MIN_BRIGHTNESS, 1f) }
    }

    suspend fun setMoveContent(enabled: Boolean) {
        store.edit { it[MOVE_CONTENT] = enabled }
    }

    suspend fun setShowBattery(show: Boolean) {
        store.edit { it[SHOW_BATTERY] = show }
    }

    suspend fun setUnlockMethod(method: UnlockMethod) {
        store.edit { it[UNLOCK_METHOD] = method.name }
    }

    suspend fun setSensorY(y: Float) {
        store.edit { it[SENSOR_Y] = y.coerceIn(0f, 1f) }
    }

    suspend fun setShowSensorRing(show: Boolean) {
        store.edit { it[SHOW_SENSOR_RING] = show }
    }

    private fun readClock(p: Preferences) = ClockSettings(
        style = enumOr(p[CLOCK_STYLE], ClockStyle.DIGITAL),
        system = enumOr(p[CLOCK_SYSTEM], TimeSystem.STANDARD),
        hourFormat = enumOr(p[CLOCK_HOUR_FORMAT], HourFormat.SYSTEM),
        showAmPm = p[CLOCK_AM_PM] ?: false,
        leadingZero = enumOr(p[CLOCK_LEADING_ZERO], LeadingZero.AUTO),
        showSeconds = p[CLOCK_SECONDS] ?: false,
        zone = p[CLOCK_ZONE] ?: "",
        showZoneLabel = p[CLOCK_ZONE_LABEL] ?: false,
        secondClock = p[CLOCK_SECOND_CLOCK] ?: false,
        secondZone = p[CLOCK_SECOND_ZONE] ?: "UTC",
        scale = p[CLOCK_SCALE] ?: 1f,
        weight = enumOr(p[CLOCK_WEIGHT], ClockWeight.THIN),
        font = enumOr(p[CLOCK_FONT], ClockFont.SANS),
        color = enumOr(p[CLOCK_COLOR], ClockColor.GREY),
        ticks = enumOr(p[CLOCK_TICKS], DialTicks.HOURS),
        numerals = enumOr(p[CLOCK_NUMERALS], DialNumerals.NONE),
        hands = enumOr(p[CLOCK_HANDS], HandStyle.LINE),
        dialRing = p[CLOCK_DIAL_RING] ?: true,
    )

    private fun writeClock(p: MutablePreferences, c: ClockSettings) {
        p[CLOCK_STYLE] = c.style.name
        p[CLOCK_SYSTEM] = c.system.name
        p[CLOCK_HOUR_FORMAT] = c.hourFormat.name
        p[CLOCK_AM_PM] = c.showAmPm
        p[CLOCK_LEADING_ZERO] = c.leadingZero.name
        p[CLOCK_SECONDS] = c.showSeconds
        p[CLOCK_ZONE] = c.zone
        p[CLOCK_ZONE_LABEL] = c.showZoneLabel
        p[CLOCK_SECOND_CLOCK] = c.secondClock
        p[CLOCK_SECOND_ZONE] = c.secondZone
        p[CLOCK_SCALE] = c.scale.coerceIn(CLOCK_SCALE_RANGE)
        p[CLOCK_WEIGHT] = c.weight.name
        p[CLOCK_FONT] = c.font.name
        p[CLOCK_COLOR] = c.color.name
        p[CLOCK_TICKS] = c.ticks.name
        p[CLOCK_NUMERALS] = c.numerals.name
        p[CLOCK_HANDS] = c.hands.name
        p[CLOCK_DIAL_RING] = c.dialRing
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val SHORTCUTS = stringPreferencesKey("shortcuts")
        val SHORTCUT_LAYOUT = stringPreferencesKey("shortcut_layout")
        val HIDDEN_APPS = stringSetPreferencesKey("hidden_apps")
        val SHOW_HIDDEN_CONTENT = booleanPreferencesKey("show_hidden_content")
        val SHOW_NOTIFICATION_COUNT = booleanPreferencesKey("show_notification_count")
        val MODAL_CLOSE_ACTION = stringPreferencesKey("modal_close_action")
        val MODAL_AUTO_CLOSE = booleanPreferencesKey("modal_auto_close")
        val MODAL_AUTO_CLOSE_SECONDS = intPreferencesKey("modal_auto_close_seconds")
        val POWER_BUTTON = stringPreferencesKey("power_button")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val MOVE_CONTENT = booleanPreferencesKey("move_content")
        val SHOW_BATTERY = booleanPreferencesKey("show_battery")
        val UNLOCK_METHOD = stringPreferencesKey("unlock_method")
        val SENSOR_Y = floatPreferencesKey("sensor_y")
        val SHOW_SENSOR_RING = booleanPreferencesKey("show_sensor_ring")
        val CLOCK_STYLE = stringPreferencesKey("clock_style")
        val CLOCK_SYSTEM = stringPreferencesKey("clock_system")
        val CLOCK_HOUR_FORMAT = stringPreferencesKey("clock_hour_format")
        val CLOCK_AM_PM = booleanPreferencesKey("clock_am_pm")
        val CLOCK_LEADING_ZERO = stringPreferencesKey("clock_leading_zero")
        val CLOCK_SECONDS = booleanPreferencesKey("clock_seconds")
        val CLOCK_ZONE = stringPreferencesKey("clock_zone")
        val CLOCK_ZONE_LABEL = booleanPreferencesKey("clock_zone_label")
        val CLOCK_SECOND_CLOCK = booleanPreferencesKey("clock_second_clock")
        val CLOCK_SECOND_ZONE = stringPreferencesKey("clock_second_zone")
        val CLOCK_SCALE = floatPreferencesKey("clock_scale")
        val CLOCK_WEIGHT = stringPreferencesKey("clock_weight")
        val CLOCK_FONT = stringPreferencesKey("clock_font")
        val CLOCK_COLOR = stringPreferencesKey("clock_color")
        val CLOCK_TICKS = stringPreferencesKey("clock_ticks")
        val CLOCK_NUMERALS = stringPreferencesKey("clock_numerals")
        val CLOCK_HANDS = stringPreferencesKey("clock_hands")
        val CLOCK_DIAL_RING = booleanPreferencesKey("clock_dial_ring")
    }
}
