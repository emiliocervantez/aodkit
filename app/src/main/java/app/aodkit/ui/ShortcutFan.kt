package app.aodkit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.aodkit.ShortcutLayout

private val ICON_SIZE = 48.dp
private val GAP = 12.dp

/**
 * Shortcuts fanned out from the Shortcut Launcher button: a column rising above it, or a row
 * extending left. Shortcut #1 sits next to the button. A column or row that doesn't fit scrolls and opens
 * at its far end (top or left), so it starts with the top Shortcut of the settings list.
 */
@Composable
fun ShortcutFan(shortcuts: List<String>, layout: ShortcutLayout, onClose: () -> Unit, onLaunch: (String) -> Unit) {
    BackHandler(onBack = onClose)
    val farToNear = shortcuts.reversed()
    Box(
        Modifier
            .fillMaxSize()
            .background(Scrim)
            .clickable(interactionSource = null, indication = null, onClick = onClose),
    ) {
        // Same placement as the real button underneath, which this copy covers and which closes the fan.
        val anchor = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(LAUNCHER_PADDING)
        when (layout) {
            ShortcutLayout.VERTICAL -> Column(anchor.width(LAUNCHER_SIZE), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = GAP),
                    verticalArrangement = Arrangement.spacedBy(GAP),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    farToNear.forEach { ShortcutIcon(it, onLaunch) }
                }
                ShortcutLauncherButton(Modifier, onClick = onClose)
            }
            ShortcutLayout.HORIZONTAL -> Row(anchor, verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f, fill = false)
                        .horizontalScroll(rememberScrollState())
                        .padding(end = GAP),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    farToNear.forEach { ShortcutIcon(it, onLaunch) }
                }
                ShortcutLauncherButton(Modifier, onClick = onClose)
            }
        }
    }
}

@Composable
private fun ShortcutIcon(packageName: String, onLaunch: (String) -> Unit) {
    AppIcon(
        packageName,
        Modifier
            .size(ICON_SIZE)
            .clip(CircleShape)
            .clickable { onLaunch(packageName) },
    )
}
