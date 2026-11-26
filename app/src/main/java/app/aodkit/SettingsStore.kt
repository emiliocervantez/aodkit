package app.aodkit

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val MAX_SHORTCUTS = 8

/** What a power-button press does while the AOD Screen is showing. */
enum class PowerButtonAction { DISPLAY_OFF, LOCK_SCREEN }

data class AodSettings(
    val enabled: Boolean = false,
    /** Shortcut package names, in display order. */
    val shortcuts: List<String> = emptyList(),
    val hiddenApps: Set<String> = emptySet(),
    val powerButton: PowerButtonAction = PowerButtonAction.DISPLAY_OFF,
    /** Window brightness of the AOD Screen, 0..1. */
    val brightness: Float = DEFAULT_BRIGHTNESS,
    /** Touching the fingerprint sensor area closes the AOD Screen so the Lock Screen's sensor can take over. */
    val fingerprintUnlock: Boolean = true,
    /** Vertical centre of the in-display fingerprint sensor, as a fraction of the screen height. */
    val sensorY: Float = DEFAULT_SENSOR_Y,
)

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
            hiddenApps = p[HIDDEN_APPS].orEmpty(),
            powerButton = p[POWER_BUTTON]
                ?.let { name -> PowerButtonAction.entries.firstOrNull { it.name == name } }
                ?: PowerButtonAction.DISPLAY_OFF,
            brightness = p[BRIGHTNESS] ?: DEFAULT_BRIGHTNESS,
            fingerprintUnlock = p[FINGERPRINT_UNLOCK] ?: true,
            sensorY = p[SENSOR_Y] ?: DEFAULT_SENSOR_Y,
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    suspend fun setShortcuts(packages: List<String>) {
        store.edit { it[SHORTCUTS] = packages.take(MAX_SHORTCUTS).joinToString(",") }
    }

    suspend fun setHiddenApps(packages: Set<String>) {
        store.edit { it[HIDDEN_APPS] = packages }
    }

    suspend fun setPowerButton(action: PowerButtonAction) {
        store.edit { it[POWER_BUTTON] = action.name }
    }

    suspend fun setBrightness(brightness: Float) {
        store.edit { it[BRIGHTNESS] = brightness.coerceIn(MIN_BRIGHTNESS, 1f) }
    }

    suspend fun setFingerprintUnlock(enabled: Boolean) {
        store.edit { it[FINGERPRINT_UNLOCK] = enabled }
    }

    suspend fun setSensorY(y: Float) {
        store.edit { it[SENSOR_Y] = y.coerceIn(0f, 1f) }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val SHORTCUTS = stringPreferencesKey("shortcuts")
        val HIDDEN_APPS = stringSetPreferencesKey("hidden_apps")
        val POWER_BUTTON = stringPreferencesKey("power_button")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val FINGERPRINT_UNLOCK = booleanPreferencesKey("fingerprint_unlock")
        val SENSOR_Y = floatPreferencesKey("sensor_y")
    }
}
