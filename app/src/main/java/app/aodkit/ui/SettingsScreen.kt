package app.aodkit.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import app.aodkit.AodActivity
import app.aodkit.AodNotificationListener
import app.aodkit.MAX_SHORTCUTS
import app.aodkit.MIN_BRIGHTNESS
import app.aodkit.PowerButtonAction
import app.aodkit.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class Permissions(val notificationAccess: Boolean, val overlay: Boolean, val batteryUnrestricted: Boolean) {
    val required get() = notificationAccess && overlay
}

private data class LaunchableApp(val packageName: String, val label: String)

private enum class Picker { SHORTCUTS, HIDDEN_APPS }

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

    Scaffold { padding ->
        if (settings == null) return@Scaffold
        LazyColumn(contentPadding = padding) {
            item { Text("aodkit", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium) }

            item { SectionHeader("Permissions") }
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
                        },
                        Modifier.padding(start = 16.dp),
                    )
                }
            }

            item { SectionHeader("AOD brightness") }
            item {
                BrightnessSlider(settings.brightness) { scope.launch { store.setBrightness(it) } }
            }

            item { SectionHeader("Shortcuts (${settings.shortcuts.size}/$MAX_SHORTCUTS)") }
            itemsIndexed(settings.shortcuts, key = { _, pkg -> pkg }) { index, pkg ->
                ShortcutRow(
                    packageName = pkg,
                    onUp = if (index > 0) ({ scope.launch { store.setShortcuts(settings.shortcuts.swap(index, index - 1)) } }) else null,
                    onDown = if (index < settings.shortcuts.lastIndex) ({ scope.launch { store.setShortcuts(settings.shortcuts.swap(index, index + 1)) } }) else null,
                    onRemove = { scope.launch { store.setShortcuts(settings.shortcuts - pkg) } },
                )
            }
            item { TextButton(onClick = { picker = Picker.SHORTCUTS }, Modifier.padding(horizontal = 8.dp)) { Text("Choose shortcuts") } }

            item { SectionHeader("Hidden apps") }
            item {
                SettingRow("Hidden apps", "${settings.hiddenApps.size} apps never get a notification icon.") {
                    picker = Picker.HIDDEN_APPS
                }
            }

            item {
                Button(
                    onClick = { context.startActivity(Intent(context, AodActivity::class.java)) },
                    Modifier.padding(16.dp).fillMaxWidth(),
                ) { Text("Preview AOD Screen") }
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
        null -> Unit
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingRow(
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

/** Saves only when the drag ends, so the store isn't written on every frame. */
@Composable
private fun BrightnessSlider(saved: Float, onSave: (Float) -> Unit) {
    var value by remember(saved) { mutableFloatStateOf(saved) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onSave(value) },
            valueRange = MIN_BRIGHTNESS..1f,
            modifier = Modifier.weight(1f),
        )
        Text("${(value * 100).roundToInt()}%", Modifier.padding(start = 16.dp).width(48.dp))
    }
}

@Composable
private fun ShortcutRow(packageName: String, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onRemove: () -> Unit) {
    val context = LocalContext.current
    val label = remember(packageName) { appLabel(context, packageName) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName, Modifier.size(36.dp))
        Text(label, Modifier.weight(1f).padding(start = 16.dp))
        IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Text("↑") }
        IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Text("↓") }
        IconButton(onClick = onRemove) { Text("✕") }
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

private fun <T> List<T>.swap(i: Int, j: Int): List<T> = toMutableList().apply { this[i] = this[j].also { this[j] = this[i] } }
