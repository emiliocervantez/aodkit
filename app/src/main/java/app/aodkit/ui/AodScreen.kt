package app.aodkit.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.aodkit.AodNotification
import app.aodkit.AppNotifications
import kotlinx.coroutines.delay
import java.util.Date

internal val AodGrey = Color(0xFF8E8E8E)
private const val MAX_ICONS = 6
private const val MINUTE_MS = 60_000L
private const val REVEAL_MS = 300

private val SWIPE_UP_DISTANCE = 100.dp

/** Matches the A56 sensor's touch rect (225 px at 450 dpi). */
private val SENSOR_ZONE = 80.dp

/** Burn-in protection: the content block steps through these offsets (dp), one per minute. */
private val burnInSteps = listOf(0 to 0, 4 to 2, 2 to -4, -4 to -2, -2 to 4, 4 to -4, -4 to 4, 0 to -6)

/** [revealed] stays false (pure black) until the AOD brightness is in effect, then content fades in. */
@Composable
fun AodScreen(
    revealed: Boolean,
    apps: List<AppNotifications>,
    shortcuts: List<String>,
    /** Fingerprint sensor centre as a fraction of the screen height, or null when the feature is off. */
    sensorY: Float?,
    onSensorTouch: () -> Unit,
    /** Null when swipe-to-unlock is off. */
    onSwipeUp: (() -> Unit)?,
    onExit: () -> Unit,
    onDismiss: (String) -> Unit,
    onOpen: (AodNotification) -> Unit,
    onLaunchShortcut: (String) -> Unit,
) {
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var shortcutsOpen by remember { mutableStateOf(false) }
    val selectedApp = apps.firstOrNull { it.packageName == selectedPackage }
    // The modal closes once its app has no notifications left.
    LaunchedEffect(selectedApp) { if (selectedApp == null) selectedPackage = null }
    val now = rememberMinuteTicker()
    val contentAlpha by animateFloatAsState(if (revealed) 1f else 0f, tween(REVEAL_MS), label = "reveal")
    val swipeEnabled = onSwipeUp != null
    val currentOnSwipeUp by rememberUpdatedState(onSwipeUp)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { alpha = contentAlpha }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onExit() }) }
            .pointerInput(swipeEnabled) {
                if (!swipeEnabled) return@pointerInput
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, dy -> dragged += dy },
                    onDragEnd = { if (dragged < -SWIPE_UP_DISTANCE.toPx()) currentOnSwipeUp?.invoke() },
                )
            },
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .offset {
                    val (x, y) = burnInSteps[((now / MINUTE_MS) % burnInSteps.size).toInt()]
                    IntOffset(x.dp.roundToPx(), y.dp.roundToPx())
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Clock(now)
            Spacer(Modifier.height(12.dp))
            DateLine(now)
            Spacer(Modifier.height(16.dp))
            BatteryLine()
            Spacer(Modifier.height(40.dp))
            NotificationIconRow(apps, onClick = { selectedPackage = it })
        }

        // Declared before the modal and sheet so their scrims sit on top and take touches first.
        if (sensorY != null) SensorZone(sensorY, onSensorTouch)

        if (shortcuts.isNotEmpty()) {
            ShortcutLauncherButton(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(24.dp),
                onClick = { shortcutsOpen = true },
            )
        }

        if (selectedApp != null) {
            NotificationModal(
                app = selectedApp,
                onClose = { selectedPackage = null },
                onDismiss = onDismiss,
                onOpen = onOpen,
            )
        }

        if (shortcutsOpen) {
            ShortcutSheet(
                shortcuts = shortcuts,
                onClose = { shortcutsOpen = false },
                onLaunch = {
                    shortcutsOpen = false
                    onLaunchShortcut(it)
                },
            )
        }
    }
}

@Composable
private fun rememberMinuteTicker(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun Clock(now: Long) {
    val context = LocalContext.current
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    Text(
        text = DateFormat.format(pattern, Date(now)).toString(),
        color = AodGrey,
        fontSize = 96.sp,
        fontWeight = FontWeight.Thin,
    )
}

@Composable
private fun DateLine(now: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "EEEMMMd") }
    Text(
        text = DateFormat.format(pattern, Date(now)).toString().uppercase(locale),
        color = AodGrey,
        fontSize = 22.sp,
        fontWeight = FontWeight.Light,
    )
}

@Composable
private fun BatteryLine() {
    val percent = rememberBatteryPercent() ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(width = 10.dp, height = 18.dp)) {
            val nub = 2.dp.toPx()
            drawRect(AodGrey, topLeft = Offset(size.width * 0.3f, 0f), size = Size(size.width * 0.4f, nub))
            drawRoundRect(
                AodGrey,
                topLeft = Offset(0f, nub),
                size = Size(size.width, size.height - nub),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
        Text("$percent%", color = AodGrey, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun rememberBatteryPercent(): Int? {
    val context = LocalContext.current
    var percent by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                percent = intent.batteryPercent()
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { percent = it.batteryPercent() }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percent
}

private fun Intent.batteryPercent(): Int? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else null
}

@Composable
private fun NotificationIconRow(apps: List<AppNotifications>, onClick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        apps.take(MAX_ICONS).forEach { app ->
            AppIcon(
                packageName = app.packageName,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { onClick(app.packageName) },
                grayscale = true,
            )
        }
        if (apps.size > MAX_ICONS) {
            Text("+${apps.size - MAX_ICONS}", color = AodGrey, fontSize = 20.sp)
        }
    }
}

/** A faint ring over the in-display fingerprint sensor; any touch landing on it fires [onTouch]. */
@Composable
private fun SensorZone(centerY: Float, onTouch: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = maxHeight * centerY - SENSOR_ZONE / 2)
                .size(SENSOR_ZONE)
                .border(1.dp, AodGrey.copy(alpha = 0.5f), CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onTouch()
                    }
                },
        )
    }
}

/** The Shortcut Launcher: three lines of decreasing length, as in the reference screenshot. */
@Composable
private fun ShortcutLauncherButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.width(28.dp).height(20.dp)) {
            val stroke = 3.dp.toPx()
            listOf(1f, 0.75f, 0.45f).forEachIndexed { i, fraction ->
                val y = stroke / 2 + i * (size.height - stroke) / 2
                drawLine(
                    AodGrey,
                    start = Offset(size.width * (1 - fraction), y),
                    end = Offset(size.width, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
