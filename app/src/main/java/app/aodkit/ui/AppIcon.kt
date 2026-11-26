package app.aodkit.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ICON_PX = 144
private val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, grayscale: Boolean = false) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap == null) {
        Box(modifier.background(Color.DarkGray, CircleShape))
    } else {
        Image(bitmap, contentDescription = null, modifier = modifier, colorFilter = if (grayscale) Grayscale else null)
    }
}

fun appLabel(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)
