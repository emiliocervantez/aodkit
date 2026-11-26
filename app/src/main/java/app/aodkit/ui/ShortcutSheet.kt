package app.aodkit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val COLUMNS = 4

/** The Shortcut grid, slid up from the bottom inside the AOD Screen's own window. */
@Composable
fun ShortcutSheet(shortcuts: List<String>, onClose: () -> Unit, onLaunch: (String) -> Unit) {
    BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(Scrim)
            .clickable(interactionSource = null, indication = null, onClick = onClose),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(PanelColor, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(interactionSource = null, indication = null) {} // Taps inside don't close.
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            shortcuts.chunked(COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { pkg -> ShortcutItem(pkg, onClick = { onLaunch(pkg) }) }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.width(ITEM_WIDTH)) }
                }
            }
        }
    }
}

private val ITEM_WIDTH = 72.dp

@Composable
private fun ShortcutItem(packageName: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = remember(packageName) { appLabel(context, packageName) }
    Column(
        Modifier
            .width(ITEM_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppIcon(packageName, Modifier.size(52.dp).clip(CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
