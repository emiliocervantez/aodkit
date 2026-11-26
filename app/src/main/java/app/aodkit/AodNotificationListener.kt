package app.aodkit

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Feeds notifications to the AOD Screen and launches it when the display turns off.
 * The system keeps this service bound, so it doubles as the long-lived trigger host (ADR 0001).
 */
class AodNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: StateFlow<AodSettings>
    private var connected = false
    private var hiddenAppsJob: Job? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this).settings.stateIn(scope, SharingStarted.Eagerly, AodSettings())
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        connected = true
        instance = WeakReference(this)
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        hiddenAppsJob = scope.launch {
            settings.map { it.hiddenApps }.distinctUntilChanged().collect { refresh() }
        }
        refresh()
    }

    override fun onListenerDisconnected() {
        connected = false
        instance = null
        hiddenAppsJob?.cancel()
        unregisterReceiver(screenOffReceiver)
        NotificationRepository.update(emptyList())
        // Samsung occasionally unbinds listeners; ask to be bound again.
        requestRebind(ComponentName(this, AodNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = refresh()

    private fun refresh() {
        if (!connected) return
        val active = try {
            activeNotifications
        } catch (_: SecurityException) {
            null
        } ?: return
        val ranking = currentRanking
        NotificationRepository.update(group(active.map { it.toRaw(ranking) }, settings.value.hiddenApps))
    }

    private fun onScreenOff() {
        if (AodActivity.current != null) {
            // The display went off while the AOD Screen was up: a power press or the proximity
            // sensor. Give the proximity reading a moment to arrive before deciding which.
            handler.postDelayed(::onScreenOffWhileAodShown, PROXIMITY_SETTLE_MS)
            return
        }
        val s = settings.value
        if (!s.enabled || !Settings.canDrawOverlays(this) || isInCall()) return
        startActivity(Intent(this, AodActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun onScreenOffWhileAodShown() {
        val aod = AodActivity.current ?: return
        if (AodActivity.proximityNear) return
        aod.finish()
        if (settings.value.powerButton == PowerButtonAction.LOCK_SCREEN) wakeToLockScreen()
    }

    @Suppress("DEPRECATION") // No non-deprecated way for an app to turn the display on without an activity.
    private fun wakeToLockScreen() {
        getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "aodkit:wake")
            ?.acquire(WAKE_HOLD_MS)
    }

    private fun isInCall(): Boolean {
        val mode = getSystemService<AudioManager>()?.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    companion object {
        private const val PROXIMITY_SETTLE_MS = 300L
        private const val WAKE_HOLD_MS = 1_000L
        private const val MAX_MESSAGES = 5

        @Volatile
        private var instance: WeakReference<AodNotificationListener>? = null

        /** Dismiss: clears the notification. No unlock needed. */
        fun dismiss(key: String) {
            instance?.get()?.cancelNotification(key)
        }

        private fun StatusBarNotification.toRaw(ranking: RankingMap): RawNotification {
            val n = notification
            val r = Ranking()
            // The user's per-channel lock-screen setting; not exposed before API 31.
            val override = if (Build.VERSION.SDK_INT >= 31 && ranking.getRanking(key, r)) {
                r.lockscreenVisibilityOverride
            } else {
                NO_OVERRIDE
            }
            val extras = n.extras
            val publicExtras = n.publicVersion?.extras
            return RawNotification(
                key = key,
                packageName = packageName,
                postTime = postTime,
                ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
                groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                visibility = n.visibility,
                userVisibilityOverride = override.takeIf { it != NO_OVERRIDE },
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
                messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
                    ?.messages
                    ?.takeLast(MAX_MESSAGES)
                    ?.mapNotNull { m -> m.text?.let { AodMessage(m.person?.name?.toString(), it.toString()) } }
                    .orEmpty(),
                publicTitle = publicExtras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                publicText = publicExtras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                contentIntent = n.contentIntent,
            )
        }

        // NotificationManager.VISIBILITY_NO_OVERRIDE
        private const val NO_OVERRIDE = -1000
    }
}
