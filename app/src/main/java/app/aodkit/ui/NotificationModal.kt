package app.aodkit.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.aodkit.AodNotification
import app.aodkit.AppNotifications
import kotlinx.coroutines.delay
import java.util.Date

private const val MAX_HEIGHT_FRACTION = 0.8f
internal val PanelColor = Color(0xFF1C1C1C)
internal val Scrim = Color.Black.copy(alpha = 0.7f)

/**
 * The Notification Modal. It is drawn inside the AOD Screen's own window rather than a dialog window,
 * so it inherits the window's show-when-locked state and minimum brightness.
 */
@Composable
fun NotificationModal(
    app: AppNotifications,
    /** When true the ✕ button dismisses all of [app]'s notifications before closing. */
    closeButtonDismisses: Boolean,
    /** Close after this long without a touch; null keeps the modal open until the user closes it. */
    autoCloseMs: Long?,
    onClose: () -> Unit,
    onDismiss: (String) -> Unit,
    onOpen: (AodNotification) -> Unit,
) {
    val context = LocalContext.current
    val label = remember(app.packageName) { appLabel(context, app.packageName) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastInteraction, autoCloseMs) {
        if (autoCloseMs == null) return@LaunchedEffect
        delay(autoCloseMs)
        onClose()
    }
    BackHandler(onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            .background(Scrim)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastInteraction = System.currentTimeMillis()
                    }
                }
            }
            .clickable(interactionSource = null, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                // Grows with its content; beyond this the list scrolls.
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * MAX_HEIGHT_FRACTION)
                .background(PanelColor, RoundedCornerShape(24.dp))
                .clickable(interactionSource = null, indication = null) {} // Taps inside don't close.
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(app.packageName, Modifier.size(32.dp))
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                IconButton(
                    onClick = {
                        if (closeButtonDismisses) app.notifications.forEach { onDismiss(it.key) }
                        onClose()
                    },
                ) { Text("✕", style = MaterialTheme.typography.titleMedium) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(app.notifications, key = { it.key }) { n ->
                    NotificationItem(n, onDismiss = { onDismiss(n.key) }, onOpen = { onOpen(n) })
                }
            }
        }
    }
}

@Composable
private fun NotificationItem(n: AodNotification, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                n.title.orEmpty(),
                Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                DateFormat.getTimeFormat(context).format(Date(n.postTime)),
                style = MaterialTheme.typography.labelMedium,
                color = AodGrey,
            )
        }
        when {
            n.contentHidden -> Text("Content hidden", fontStyle = FontStyle.Italic, color = AodGrey)
            n.messages.isNotEmpty() -> n.messages.forEach { m ->
                Text(
                    buildAnnotatedString {
                        m.sender?.let { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$it: ") } }
                        append(m.text)
                    },
                )
            }
            n.text != null -> Text(n.text)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            if (n.contentIntent != null) TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}
