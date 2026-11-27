package app.aodkit.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import app.aodkit.AodActivity
import app.aodkit.AUTO_CLOSE_SECONDS_RANGE
import app.aodkit.AodNotificationListener
import app.aodkit.MAX_SHORTCUTS
import app.aodkit.MIN_BRIGHTNESS
import app.aodkit.ModalCloseAction
import app.aodkit.PowerButtonAction
import app.aodkit.R
import app.aodkit.SettingsStore
import app.aodkit.ShortcutLayout
import app.aodkit.UnlockMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class Permissions(val notificationAccess: Boolean, val overlay: Boolean, val batteryUnrestricted: Boolean) {
    val required get() = notificationAccess && overlay
}

private data class LaunchableApp(val packageName: String, val label: String)

private enum class Picker { SHORTCUTS, HIDDEN_APPS, CLOCK_ZONE, SECOND_CLOCK_ZONE }

private enum class SettingsGroup(val title: String) {
    PERMISSIONS("Permissions"),
    CLOCK("Clock"),
    DISPLAY("Display"),
    UNLOCK("Unlock & power button"),
    NOTIFICATIONS("Notifications"),
    SHORTCUTS("Shortcuts"),
}

@Composable
fun SettingsScreen(store: SettingsStore, resumeCount: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = store.settings.collectAsState(initial = null).value
    val permissions = remember(resumeCount) { readPermissions(context) }
    val apps by produceState(emptyList<LaunchableApp>()) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var group by rememberSaveable { mutableStateOf<SettingsGroup?>(null) }
    val system24h = DateFormat.is24HourFormat(context)
    BackHandler(enabled = group != null) { group = null }

    Scaffold { padding ->
        if (settings == null) return@Scaffold
        // Keyed by page, so each page opens scrolled to the top.
        key(group) {
            val listState = rememberLazyListState()
            // The Shortcut list mirrors the AOD Screen, where Shortcut #1 sits next to the button, so it is
            // shown bottom-up: its top is the topmost (vertical) or leftmost (horizontal) icon there.
            val shownShortcuts = settings.shortcuts.reversed()
            val reorder = remember(shownShortcuts) { ShortcutReorder(shownShortcuts, listState) }
            LazyColumn(state = listState, contentPadding = padding) {
                when (group) {
                    null -> {
                        item { Text("aodkit", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium) }

                        item {
                            SettingRow(
                                "Enable AOD",
                                if (permissions.required) "Show the AOD Screen when the display turns off." else "Grant the required permissions first.",
                                trailing = {
                                    Switch(
                                        checked = settings.enabled && permissions.required,
                                        enabled = permissions.required,
                                        onCheckedChange = { scope.launch { store.setEnabled(it) } },
                                    )
                                },
                            )
                        }

                        items(SettingsGroup.entries) { g ->
                            SettingRow(
                                g.title,
                                when (g) {
                                    SettingsGroup.PERMISSIONS -> if (permissions.required) "All required permissions granted" else "Required permissions missing"
                                    SettingsGroup.CLOCK -> clockSummary(settings.clock, system24h)
                                    SettingsGroup.DISPLAY -> "Brightness, battery level, burn-in protection"
                                    SettingsGroup.UNLOCK -> "Unlock method, sensor, power button"
                                    SettingsGroup.NOTIFICATIONS -> "Icons, notification window, hidden apps"
                                    SettingsGroup.SHORTCUTS -> "${settings.shortcuts.size}/$MAX_SHORTCUTS apps, layout"
                                },
                                trailing = { Text("›", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleLarge) },
                                onClick = { group = g },
                            )
                        }

                        item { PreviewButton() }
                    }

                    SettingsGroup.PERMISSIONS -> {
                        item { GroupHeader(SettingsGroup.PERMISSIONS) { group = null } }
                        item {
                            PermissionRow("Notification access", "Required. Shows notification icons.", permissions.notificationAccess) {
                                context.startFirst(notificationAccessIntents(context))
                            }
                        }
                        item {
                            PermissionRow("Display over other apps", "Required. Lets the AOD Screen appear when the display turns off.", permissions.overlay) {
                                context.startFirst(listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())))
                            }
                        }
                        item {
                            PermissionRow(
                                "Ignore battery optimisation",
                                "Recommended. On Samsung, also add aodkit to Battery › Background usage limits › Never sleeping apps.",
                                permissions.batteryUnrestricted,
                            ) { context.startFirst(listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) }
                        }
                        item {
                            SettingRow("Turn off the system AOD", "Samsung: Settings › Lock screen and AOD › Always On Display.") {
                                context.startFirst(listOf(Intent(Settings.ACTION_SETTINGS)))
                            }
                        }
                    }

                    SettingsGroup.CLOCK -> {
                        item { GroupHeader(SettingsGroup.CLOCK) { group = null } }
                        clockSettingsItems(
                            clock = settings.clock,
                            system24h = system24h,
                            update = { transform -> scope.launch { store.updateClock(transform) } },
                            onPickZone = { second -> picker = if (second) Picker.SECOND_CLOCK_ZONE else Picker.CLOCK_ZONE },
                        )
                        item { PreviewButton() }
                    }

                    SettingsGroup.DISPLAY -> {
                        item { GroupHeader(SettingsGroup.DISPLAY) { group = null } }
                        item { SectionHeader("AOD brightness") }
                        item {
                            PercentSlider(settings.brightness, MIN_BRIGHTNESS..1f) { scope.launch { store.setBrightness(it) } }
                        }

                        item { SectionHeader("Battery level") }
                        item {
                            SettingRow(
                                "Show battery level",
                                "The battery percentage under the date.",
                                trailing = {
                                    Switch(
                                        checked = settings.showBattery,
                                        onCheckedChange = { scope.launch { store.setShowBattery(it) } },
                                    )
                                },
                            )
                        }

                        item { SectionHeader("Burn-in protection") }
                        item {
                            SettingRow(
                                "Move content around the screen",
                                if (settings.moveContent) {
                                    "Every minute the clock, date, battery and icons jump to a random spot. The shortcut button and fingerprint circle stay put."
                                } else {
                                    "Content stays centred and shifts by a few pixels each minute."
                                },
                                trailing = {
                                    Switch(
                                        checked = settings.moveContent,
                                        onCheckedChange = { scope.launch { store.setMoveContent(it) } },
                                    )
                                },
                            )
                        }
                    }

                    SettingsGroup.UNLOCK -> {
                        item { GroupHeader(SettingsGroup.UNLOCK) { group = null } }
                        item { SectionHeader("Unlock with") }
                        items(UnlockMethod.entries) { method ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(settings.unlockMethod == method) { scope.launch { store.setUnlockMethod(method) } }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = settings.unlockMethod == method, onClick = null)
                                Column(Modifier.padding(start = 16.dp)) {
                                    Text(
                                        when (method) {
                                            UnlockMethod.FINGERPRINT -> "Fingerprint"
                                            UnlockMethod.SWIPE_LOCK_SCREEN -> "Swipe to lock screen"
                                            UnlockMethod.SWIPE_PIN -> "Swipe to PIN screen"
                                        },
                                    )
                                    Text(
                                        when (method) {
                                            UnlockMethod.FINGERPRINT -> "Touch the ring over the sensor, then touch the sensor again."
                                            UnlockMethod.SWIPE_LOCK_SCREEN -> "Swipe up shows the lock screen; then touch the fingerprint sensor."
                                            UnlockMethod.SWIPE_PIN -> "Swipe up opens the PIN screen; fingerprint and face work there too."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (settings.unlockMethod == UnlockMethod.FINGERPRINT) {
                            item {
                                SettingRow(
                                    "Show circle over the sensor",
                                    "When hidden, touching the sensor spot still works.",
                                    trailing = {
                                        Switch(
                                            checked = settings.showSensorRing,
                                            onCheckedChange = { scope.launch { store.setShowSensorRing(it) } },
                                        )
                                    },
                                )
                            }
                            item {
                                Text(
                                    "Sensor position (use Preview to line the ring up)",
                                    Modifier.padding(horizontal = 16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            item {
                                PercentSlider(settings.sensorY, SENSOR_Y_RANGE) { scope.launch { store.setSensorY(it) } }
                            }
                        }

                        item { SectionHeader("Power button on the AOD Screen") }
                        items(PowerButtonAction.entries) { action ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(settings.powerButton == action) { scope.launch { store.setPowerButton(action) } }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = settings.powerButton == action, onClick = null)
                                Text(
                                    when (action) {
                                        PowerButtonAction.DISPLAY_OFF -> "Turn display off"
                                        PowerButtonAction.LOCK_SCREEN -> "Show lock screen"
                                        PowerButtonAction.KEEP_ON -> "Keep AOD on (display blinks briefly)"
                                    },
                                    Modifier.padding(start = 16.dp),
                                )
                            }
                        }

                        item { PreviewButton() }
                    }

                    SettingsGroup.SHORTCUTS -> {
                        item { GroupHeader(SettingsGroup.SHORTCUTS) { group = null } }
                        item { SectionHeader("Shortcuts (${settings.shortcuts.size}/$MAX_SHORTCUTS)") }
                        item {
                            Text(
                                "The bottom shortcut sits next to the button.",
                                Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(reorder.order ?: shownShortcuts, key = { it }) { pkg ->
                            val drop = { reorder.end()?.let { scope.launch { store.setShortcuts(it.reversed()) } } }
                            ShortcutRow(
                                packageName = pkg,
                                onRemove = { scope.launch { store.setShortcuts(settings.shortcuts - pkg) } },
                                dragHandle = Modifier.pointerInput(reorder) {
                                    detectVerticalDragGestures(
                                        onDragStart = { reorder.start(pkg) },
                                        onDragEnd = { drop() },
                                        onDragCancel = { drop() },
                                    ) { change, dy ->
                                        change.consume()
                                        reorder.drag(dy)
                                    }
                                },
                                modifier = if (reorder.dragged == pkg) {
                                    Modifier
                                        .zIndex(1f)
                                        .graphicsLayer { translationY = reorder.offset }
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                } else {
                                    Modifier.animateItem()
                                },
                            )
                        }
                        item { TextButton(onClick = { picker = Picker.SHORTCUTS }, Modifier.padding(horizontal = 8.dp)) { Text("Choose shortcuts") } }
                        items(ShortcutLayout.entries) { layout ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(settings.shortcutLayout == layout) { scope.launch { store.setShortcutLayout(layout) } }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = settings.shortcutLayout == layout, onClick = null)
                                Text(
                                    when (layout) {
                                        ShortcutLayout.VERTICAL -> "Vertical: a column above the button"
                                        ShortcutLayout.HORIZONTAL -> "Horizontal: a row left of the button"
                                    },
                                    Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }

                    SettingsGroup.NOTIFICATIONS -> {
                        item { GroupHeader(SettingsGroup.NOTIFICATIONS) { group = null } }
                        item { SectionHeader("Notification icons") }
                        item {
                            SettingRow(
                                "Show number of notifications",
                                "A small count on an app's icon when it has more than one notification.",
                                trailing = {
                                    Switch(
                                        checked = settings.showNotificationCount,
                                        onCheckedChange = { scope.launch { store.setShowNotificationCount(it) } },
                                    )
                                },
                            )
                        }

                        item { SectionHeader("✕ button in the notification window") }
                        items(ModalCloseAction.entries) { action ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(settings.modalCloseAction == action) { scope.launch { store.setModalCloseAction(action) } }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = settings.modalCloseAction == action, onClick = null)
                                Text(
                                    when (action) {
                                        ModalCloseAction.CLOSE -> "Close the window"
                                        ModalCloseAction.DISMISS_ALL -> "Dismiss the notifications"
                                    },
                                    Modifier.padding(start = 16.dp),
                                )
                            }
                        }

                        item {
                            SettingRow(
                                "Close after inactivity",
                                if (settings.modalAutoClose) {
                                    "Closes the notification window after ${settings.modalAutoCloseSeconds} s without a touch."
                                } else {
                                    "The notification window stays open until you close it."
                                },
                                trailing = {
                                    Switch(
                                        checked = settings.modalAutoClose,
                                        onCheckedChange = { scope.launch { store.setModalAutoClose(it) } },
                                    )
                                },
                            )
                        }
                        if (settings.modalAutoClose) {
                            item {
                                SecondsSlider(settings.modalAutoCloseSeconds) { scope.launch { store.setModalAutoCloseSeconds(it) } }
                            }
                        }

                        item { SectionHeader("Hidden apps") }
                        item {
                            SettingRow("Hidden apps", "${settings.hiddenApps.size} apps never get a notification icon.") {
                                picker = Picker.HIDDEN_APPS
                            }
                        }
                        item {
                            SettingRow(
                                "Show hidden content on the AOD Screen",
                                "Shows message text that your phone's lock-screen settings hide (e.g. Telegram). " +
                                    "The Samsung lock screen stays private; secret notifications stay hidden.",
                                trailing = {
                                    Switch(
                                        checked = settings.showHiddenContent,
                                        onCheckedChange = { scope.launch { store.setShowHiddenContent(it) } },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    when (picker) {
        Picker.SHORTCUTS -> AppPickerDialog(
            title = "Shortcuts",
            apps = apps,
            initial = settings?.shortcuts.orEmpty(),
            limit = MAX_SHORTCUTS,
            onDone = { scope.launch { store.setShortcuts(it) } },
            onClose = { picker = null },
        )
        Picker.HIDDEN_APPS -> AppPickerDialog(
            title = "Hidden apps",
            apps = apps,
            initial = settings?.hiddenApps.orEmpty().toList(),
            limit = null,
            onDone = { scope.launch { store.setHiddenApps(it.toSet()) } },
            onClose = { picker = null },
        )
        Picker.CLOCK_ZONE -> ZonePickerDialog(
            title = "Time zone",
            selected = settings?.clock?.zone.orEmpty(),
            includeDevice = true,
            onPick = { id -> scope.launch { store.updateClock { it.copy(zone = id) } } },
            onClose = { picker = null },
        )
        Picker.SECOND_CLOCK_ZONE -> ZonePickerDialog(
            title = "Second clock time zone",
            selected = settings?.clock?.secondZone.orEmpty(),
            includeDevice = false,
            onPick = { id -> scope.launch { store.updateClock { it.copy(secondZone = id) } } },
            onClose = { picker = null },
        )
        null -> Unit
    }
}

@Composable
private fun GroupHeader(group: SettingsGroup, onBack: () -> Unit) {
    Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
        Text(group.title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun PreviewButton() {
    val context = LocalContext.current
    Button(
        onClick = {
            context.startActivity(Intent(context, AodActivity::class.java).putExtra(AodActivity.EXTRA_PREVIEW, true))
        },
        Modifier.padding(16.dp).fillMaxWidth(),
    ) { Text("Preview AOD Screen") }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun SettingRow(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
    SettingRow(
        title,
        subtitle,
        trailing = {
            Text(
                if (granted) "✓" else "Grant",
                Modifier.padding(start = 16.dp),
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        onClick = onClick,
    )
}

/** The sensor sits in the lower part of the screen on every phone with one under the display. */
private val SENSOR_Y_RANGE = 0.5f..1f

/** Saves only when the drag ends, so the store isn't written on every frame. */
@Composable
internal fun PercentSlider(saved: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onSave: (Float) -> Unit) {
    var value by remember(saved) { mutableFloatStateOf(saved) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onSave(value) },
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f),
        )
        Text("${(value * 100).roundToInt()}%", Modifier.padding(start = 16.dp).width(48.dp))
    }
}

/** Snaps to 10-second steps across [AUTO_CLOSE_SECONDS_RANGE]; saves when the drag ends. */
@Composable
private fun SecondsSlider(saved: Int, onSave: (Int) -> Unit) {
    val range = AUTO_CLOSE_SECONDS_RANGE
    var value by remember(saved) { mutableFloatStateOf(saved.toFloat()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onSave(value.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / 10 - 1,
            modifier = Modifier.weight(1f),
        )
        Text("${value.roundToInt()} s", Modifier.padding(start = 16.dp).width(48.dp))
    }
}

@Composable
private fun ShortcutRow(packageName: String, onRemove: () -> Unit, dragHandle: Modifier, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = remember(packageName) { appLabel(context, packageName) }
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(dragHandle.size(48.dp), contentAlignment = Alignment.Center) {
            Text("≡", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppIcon(packageName, Modifier.size(36.dp))
        Text(label, Modifier.weight(1f).padding(start = 16.dp))
        IconButton(onClick = onRemove) { Text("✕") }
    }
}

/**
 * Drag-to-reorder for the Shortcut rows. All rows are the same height, so the slot under the finger is
 * the drag distance divided by the row height, with no hit-testing against a list layout that lags a frame.
 */
private class ShortcutReorder(private val shown: List<String>, private val listState: LazyListState) {
    /** The order shown while dragging and until the store emits the saved one; null shows [shown]. */
    var order by mutableStateOf<List<String>?>(null)
        private set
    var dragged by mutableStateOf<String?>(null)
        private set
    private var from = 0
    private var to = 0
    private var rowHeight = 0
    private var distance by mutableFloatStateOf(0f)

    /** Keeps the dragged row under the finger after it has moved to another slot. */
    val offset get() = distance - (to - from) * rowHeight

    fun start(key: String) {
        rowHeight = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.size ?: return
        from = shown.indexOf(key)
        to = from
        distance = 0f
        dragged = key
    }

    fun drag(dy: Float) {
        if (dragged == null) return
        distance += dy
        val target = (from + (distance / rowHeight).roundToInt()).coerceIn(0, shown.lastIndex)
        if (target != to) {
            to = target
            order = shown.toMutableList().apply { add(target, removeAt(from)) }
        }
    }

    /** Returns the order to save, or null when the row was dropped where it started. */
    fun end(): List<String>? {
        if (dragged == null) return null
        dragged = null
        if (to == from) order = null
        return order
    }
}

@Composable
private fun AppPickerDialog(
    title: String,
    apps: List<LaunchableApp>,
    initial: List<String>,
    limit: Int?,
    onDone: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    // Keeps selection order, so newly picked Shortcuts are appended after the existing ones.
    val selected = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (limit != null) "$title (${selected.size}/$limit)" else title) },
        text = {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    val canCheck = checked || limit == null || selected.size < limit
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canCheck) {
                                if (checked) selected.remove(app.packageName) else selected.add(app.packageName)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = canCheck)
                        AppIcon(app.packageName, Modifier.padding(horizontal = 12.dp).size(32.dp))
                        Text(app.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(selected.toList())
                onClose()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

private fun readPermissions(context: Context) = Permissions(
    notificationAccess = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
    overlay = Settings.canDrawOverlays(context),
    batteryUnrestricted = context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) == true,
)

private fun notificationAccessIntents(context: Context): List<Intent> = buildList {
    if (Build.VERSION.SDK_INT >= 30) {
        add(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, AodNotificationListener::class.java).flattenToString(),
            ),
        )
    }
    add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

/** Starts the first intent the device can handle (OEM settings screens vary). */
private fun Context.startFirst(intents: List<Intent>) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
}

private fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .map { LaunchableApp(it, appLabel(context, it)) }
        .sortedBy { it.label.lowercase() }
}
