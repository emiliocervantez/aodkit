package app.aodkit

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.aodkit.ui.AodScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference

/** The AOD Screen: a black, minimum-brightness activity shown over the Lock Screen. */
class AodActivity : ComponentActivity() {

    private var proximityLock: PowerManager.WakeLock? = null
    private var contentRevealed by mutableStateOf(false)
    private val sensorManager by lazy { getSystemService<SensorManager>() }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            proximityNear = event.values[0] < event.sensor.maximumRange
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentRef = WeakReference(this)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val settings = SettingsStore(this).settings
        // Read synchronously so the very first frame already has the right brightness and layout. The
        // listener in this process has loaded the store already, so this is an in-memory read.
        val initialSettings = runBlocking { settings.first() }
        applyBrightness(initialSettings.brightness)
        lifecycleScope.launch {
            settings.map { it.brightness }.distinctUntilChanged().collect(::applyBrightness)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        ContextCompat.registerReceiver(
            this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startProximity()
        // Back must not drop to the Lock Screen. Registered first, so the Notification Modal's and the
        // Shortcut fan's own Back handlers (added later) still take precedence while they are open.
        onBackPressedDispatcher.addCallback(this) {}

        setContent {
            val apps by NotificationRepository.apps.collectAsState()
            val prefs by settings.collectAsState(initial = initialSettings)
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    AodScreen(
                        revealed = contentRevealed,
                        apps = apps,
                        shortcuts = prefs.shortcuts,
                        shortcutLayout = prefs.shortcutLayout,
                        moveContent = prefs.moveContent,
                        showNotificationCount = prefs.showNotificationCount,
                        closeButtonDismisses = prefs.modalCloseAction == ModalCloseAction.DISMISS_ALL,
                        modalAutoCloseMs = (prefs.modalAutoCloseSeconds * 1_000L).takeIf { prefs.modalAutoClose },
                        // Samsung switches the sensor off while we cover the Lock Screen; step aside
                        // the moment a finger lands on it so the Lock Screen's sensor takes over.
                        sensorY = prefs.sensorY.takeIf { prefs.unlockMethod == UnlockMethod.FINGERPRINT },
                        showSensorRing = prefs.showSensorRing,
                        onSensorTouch = ::finish,
                        onSwipeUp = when (prefs.unlockMethod) {
                            UnlockMethod.FINGERPRINT -> null
                            UnlockMethod.SWIPE_LOCK_SCREEN -> ::finish
                            // Brings up Samsung's PIN/pattern screen; on cancel the AOD Screen stays.
                            UnlockMethod.SWIPE_PIN -> ({ unlockThen {} })
                        },
                        onDismiss = AodNotificationListener::dismiss,
                        onOpen = { n -> n.contentIntent?.let { unlockThen { sendContentIntent(it) } } },
                        onLaunchShortcut = { pkg -> unlockThen { launchApp(pkg) } },
                    )
                }
            }
        }
    }

    /**
     * The window's brightness only takes effect once the Lock Screen has handed over to it, which on
     * Samsung is the same moment the window first gains focus (measured: identical timestamps).
     * Until then the display runs at the user's normal brightness, so content stays hidden.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) contentRevealed = true
    }

    override fun onStop() {
        super.onStop()
        // Left while the display is still on (home gesture, a call screen on top): don't linger
        // behind, or the next screen-off would be mistaken for a power press on the AOD Screen.
        if (getSystemService<PowerManager>()?.isInteractive == true) finish()
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(proximityListener)
        proximityLock?.release()
        unregisterReceiver(unlockReceiver)
        if (currentRef?.get() === this) {
            currentRef = null
            proximityNear = false
        }
        super.onDestroy()
    }

    private fun applyBrightness(brightness: Float) {
        window.attributes = window.attributes.apply { screenBrightness = brightness }
    }

    private fun startProximity() {
        val pm = getSystemService<PowerManager>()
        if (pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "aodkit:proximity").apply {
                setReferenceCounted(false)
                acquire() // Released in onDestroy; it only blanks the display, it doesn't keep it on.
            }
        }
        sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensorManager?.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    /** Runs [action] once the Lock Screen is passed; on cancel the AOD Screen just stays. */
    private fun unlockThen(action: () -> Unit) {
        val km = getSystemService<KeyguardManager>()
        if (km == null || !km.isKeyguardLocked) {
            action()
            finish()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                action()
                finish()
            }
        })
    }

    private fun sendContentIntent(intent: PendingIntent) {
        val options = ActivityOptions.makeBasic()
        when {
            Build.VERSION.SDK_INT >= 36 -> options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE,
            )
            Build.VERSION.SDK_INT >= 34 -> @Suppress("DEPRECATION") options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }
        try {
            intent.send(this, 0, null, null, null, null, options.toBundle())
        } catch (_: PendingIntent.CanceledException) {
            // The app withdrew the intent; nothing to open.
        }
    }

    private fun launchApp(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let(::startActivity)
    }

    companion object {
        @Volatile
        private var currentRef: WeakReference<AodActivity>? = null

        /** The AOD Screen currently alive, if any. */
        val current: AodActivity? get() = currentRef?.get()

        @Volatile
        var proximityNear = false
            private set
    }
}
