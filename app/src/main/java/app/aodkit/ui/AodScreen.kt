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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.aodkit.AodNotification
import app.aodkit.AppNotifications
import app.aodkit.ClockSettings
import app.aodkit.ShortcutLayout
import app.aodkit.clockZone
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.random.Random

internal val AodGrey = Color(0xFF8E8E8E)
private const val MAX_ICONS = 6
private const val MAX_BADGE_COUNT = 99
private const val MINUTE_MS = 60_000L
private const val REVEAL_MS = 300

private val SWIPE_UP_DISTANCE = 100.dp
internal val LAUNCHER_SIZE = 56.dp
internal val LAUNCHER_PADDING = 24.dp

/** Matches the A56 sensor's touch rect (225 px at 450 dpi). */
private val SENSOR_ZONE = 80.dp

/** Burn-in protection: the content block steps through these offsets (dp), one per minute. */
private val burnInSteps = listOf(0 to 0, 4 to 2, 2 to -4, -4 to -2, -2 to 4, 4 to -4, -4 to 4, 0 to -6)

private val EDGE_MARGIN = 24.dp

/** Keeps the content below the front camera's punch hole. */
private val TOP_MARGIN = 48.dp

/** The lowest y (px) the content may reach: above the Shortcut Launcher and the fingerprint ring. */
private fun Density.contentBottom(height: Int, sensorY: Float?, reserveLauncher: Boolean): Int {
    val margin = EDGE_MARGIN.roundToPx()
    var bottom = height - margin
    if (reserveLauncher) bottom = minOf(bottom, height - (LAUNCHER_PADDING + LAUNCHER_SIZE).roundToPx() - margin)
    if (sensorY != null) bottom = minOf(bottom, (sensorY * height).toInt() - (SENSOR_ZONE / 2).roundToPx() - margin)
    return bottom
}

/**
 * Places the content at [spot] (fractions 0..1 of the free room) within the screen. It stays above the
 * fingerprint ring and the Shortcut Launcher, so it never covers either touch area.
 */
private fun Modifier.placeAt(spot: Pair<Float, Float>, sensorY: Float?, reserveLauncher: Boolean) =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = EDGE_MARGIN.roundToPx()
        val top = TOP_MARGIN.roundToPx()
        val bottom = contentBottom(height, sensorY, reserveLauncher)
        val x = margin + (spot.first * (width - 2 * margin - placeable.width).coerceAtLeast(0)).toInt()
        val y = top + (spot.second * (bottom - top - placeable.height).coerceAtLeast(0)).toInt()
        layout(width, height) { placeable.place(x, y) }
    }

/**
 * Centres the content, moved by [shift], but lifts it when it would reach the fingerprint ring or the
 * Shortcut Launcher, which a large clock otherwise can. Normal-sized content stays exactly centred.
 */
private fun Modifier.centreAbove(shift: Density.() -> IntOffset, sensorY: Float?, reserveLauncher: Boolean) =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val (dx, dy) = shift()
        val x = ((width - placeable.width) / 2f).roundToInt() + dx
        val y = (((height - placeable.height) / 2f).roundToInt() + dy)
            .coerceAtMost(contentBottom(height, sensorY, reserveLauncher) - placeable.height)
            .coerceAtLeast(TOP_MARGIN.roundToPx())
        layout(width, height) { placeable.place(x, y) }
    }

/** [revealed] stays false (pure black) until the AOD brightness is in effect, then content fades in. */
@Composable
fun AodScreen(
    revealed: Boolean,
    apps: List<AppNotifications>,
    shortcuts: List<String>,
    shortcutLayout: ShortcutLayout,
    clock: ClockSettings,
    /** Jump the content block to a random spot every minute; off keeps it centred with a small shift. */
    moveContent: Boolean,
    showBattery: Boolean,
    /** Count badge on Notification Icons of apps with more than one notification. */
    showNotificationCount: Boolean,
    closeButtonDismisses: Boolean,
    /** Null when the Notification Modal should stay open until closed. */
    modalAutoCloseMs: Long?,
    /** Fingerprint sensor centre as a fraction of the screen height, or null when the feature is off. */
    sensorY: Float?,
    showSensorRing: Boolean,
    onSensorTouch: () -> Unit,
    /** Null when swipe-to-unlock is off. */
    onSwipeUp: (() -> Unit)?,
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
        val minute = now / MINUTE_MS
        val randomSpot = remember(minute) { Random.nextFloat() to Random.nextFloat() }
        Column(
            if (moveContent) {
                Modifier.placeAt(randomSpot, sensorY, reserveLauncher = shortcuts.isNotEmpty())
            } else {
                Modifier.centreAbove(
                    shift = {
                        val (x, y) = burnInSteps[(minute % burnInSteps.size).toInt()]
                        IntOffset(x.dp.roundToPx(), y.dp.roundToPx())
                    },
                    sensorY = sensorY,
                    reserveLauncher = shortcuts.isNotEmpty(),
                )
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AodClock(clock)
            Spacer(Modifier.height(12.dp))
            DateLine(now, remember(clock.zone) { clockZone(clock.zone) })
            if (showBattery) {
                Spacer(Modifier.height(16.dp))
                BatteryLine()
            }
            Spacer(Modifier.height(40.dp))
            NotificationIconRow(apps, showNotificationCount, onClick = { selectedPackage = it })
        }

        // Declared before the modal and the fan so their scrims sit on top and take touches first.
        if (sensorY != null) SensorZone(sensorY, showSensorRing, onSensorTouch)

        if (shortcuts.isNotEmpty()) {
            ShortcutLauncherButton(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(LAUNCHER_PADDING),
                onClick = { shortcutsOpen = true },
            )
        }

        if (selectedApp != null) {
            NotificationModal(
                app = selectedApp,
                closeButtonDismisses = closeButtonDismisses,
                autoCloseMs = modalAutoCloseMs,
                onClose = { selectedPackage = null },
                onDismiss = onDismiss,
                onOpen = onOpen,
            )
        }

        if (shortcutsOpen) {
            ShortcutFan(
                shortcuts = shortcuts,
                layout = shortcutLayout,
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

/** The date in the clock's [zone], so a clock set to Tokyo shows Tokyo's date. */
@Composable
private fun DateLine(now: Long, zone: ZoneId) {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "EEEMMMd") }
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { timeInMillis = now }
    Text(
        text = DateFormat.format(pattern, calendar).toString().uppercase(locale),
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
            val stroke = 1.5.dp.toPx()
            drawRect(AodGrey, topLeft = Offset(size.width * 0.3f, 0f), size = Size(size.width * 0.4f, nub))
            // Outline of the body; a stroke is centred on its path, so inset by half its width.
            drawRoundRect(
                AodGrey,
                topLeft = Offset(stroke / 2, nub + stroke / 2),
                size = Size(size.width - stroke, size.height - nub - stroke),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
                style = Stroke(stroke),
            )
            // The level, rising from the bottom, with a small gap inside the outline.
            val inset = stroke + 1.dp.toPx()
            val level = (size.height - nub - 2 * inset) * percent.coerceIn(0, 100) / 100f
            drawRect(
                AodGrey,
                topLeft = Offset(inset, size.height - inset - level),
                size = Size(size.width - 2 * inset, level),
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
private fun NotificationIconRow(apps: List<AppNotifications>, showCount: Boolean, onClick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        apps.take(MAX_ICONS).forEach { app ->
            Box {
                AppIcon(
                    packageName = app.packageName,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onClick(app.packageName) },
                    grayscale = true,
                )
                val count = app.notifications.size
                if (showCount && count > 1) CountBadge(count, Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp))
            }
        }
        if (apps.size > MAX_ICONS) {
            Text("+${apps.size - MAX_ICONS}", color = AodGrey, fontSize = 20.sp)
        }
    }
}

/** Number of an app's notifications, grey on black like the rest of the AOD Screen. */
@Composable
private fun CountBadge(count: Int, modifier: Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(AodGrey, CircleShape)
            .border(2.dp, Color.Black, CircleShape) // Separates the badge from the icon edge.
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else "$count",
            color = Color.Black,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The in-display fingerprint sensor's spot, optionally marked by a faint ring; any touch on it fires [onTouch]. */
@Composable
private fun SensorZone(centerY: Float, showRing: Boolean, onTouch: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = maxHeight * centerY - SENSOR_ZONE / 2)
                .size(SENSOR_ZONE)
                .then(if (showRing) Modifier.border(1.dp, AodGrey.copy(alpha = 0.5f), CircleShape) else Modifier)
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
internal fun ShortcutLauncherButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(LAUNCHER_SIZE)
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
