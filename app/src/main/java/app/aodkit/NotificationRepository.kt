package app.aodkit

import android.app.Notification
import android.app.PendingIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AodMessage(val sender: String?, val text: String)

/** A notification as read from the system, before any filtering. */
data class RawNotification(
    val key: String,
    val packageName: String,
    val postTime: Long,
    /** Ongoing or foreground-service notification. */
    val ongoing: Boolean,
    val groupSummary: Boolean,
    /** The app's own lock-screen visibility. PRIVATE is Android's default, so it doesn't hide content here. */
    val visibility: Int,
    /** Lock-screen visibility the user chose for the app or channel, or null when they left it alone. */
    val userVisibilityOverride: Int?,
    val title: String?,
    val text: String?,
    val messages: List<AodMessage>,
    val publicTitle: String?,
    val publicText: String?,
    val contentIntent: PendingIntent?,
)

/** A notification as shown in the Notification Modal. */
data class AodNotification(
    val key: String,
    val postTime: Long,
    val title: String?,
    val text: String?,
    val messages: List<AodMessage>,
    val contentHidden: Boolean,
    val contentIntent: PendingIntent?,
)

/** One Notification Icon: an app and its visible notifications, newest first. */
data class AppNotifications(val packageName: String, val notifications: List<AodNotification>)

/**
 * Filters [raw] notifications down to what may be shown on the AOD Screen and groups them per app.
 * Apps are ordered by their newest notification. With [showHiddenContent], a "hide content" lock-screen
 * setting (override PRIVATE) is ignored; SECRET still drops the notification.
 */
fun group(
    raw: List<RawNotification>,
    hiddenApps: Set<String>,
    showHiddenContent: Boolean = false,
): List<AppNotifications> =
    raw.asSequence()
        .filterNot { it.ongoing || it.groupSummary || it.packageName in hiddenApps }
        .mapNotNull { r -> r.toAodNotificationOrNull(showHiddenContent)?.let { r.packageName to it } }
        .groupBy({ it.first }, { it.second })
        .map { (pkg, list) -> AppNotifications(pkg, list.sortedByDescending { it.postTime }) }
        .sortedByDescending { it.notifications.first().postTime }

/** U+034F COMBINING GRAPHEME JOINER: invisible, used in email preview padding, but not a format char. */
private const val GRAPHEME_JOINER = '͏'

/**
 * Removes the invisible padding newsletters put after their preview text (zero-width spaces and joiners,
 * soft hyphens, non-breaking spaces). Repeated spaces are squeezed, each line is trimmed and runs of
 * blank lines become a single empty line. Returns null when nothing visible is left.
 */
internal fun cleanText(text: String?): String? {
    if (text == null) return null
    val visible = buildString {
        for (c in text) {
            when {
                c == '\n' -> append('\n')
                Character.getType(c) == Character.FORMAT.toInt() || c == GRAPHEME_JOINER -> Unit
                Character.isSpaceChar(c) || c == '\t' -> append(' ')
                else -> append(c)
            }
        }
    }
    return visible.lines()
        .map { it.replace(Regex(" {2,}"), " ").trim() }
        .fold(mutableListOf<String>()) { out, line ->
            if (line.isNotEmpty() || out.lastOrNull()?.isNotEmpty() == true) out.add(line)
            out
        }
        .joinToString("\n")
        .trim()
        .ifEmpty { null }
}

private fun RawNotification.toAodNotificationOrNull(showHiddenContent: Boolean): AodNotification? {
    // Content is hidden only when the app marked it SECRET or the user's lock-screen setting asks for it.
    val override = userVisibilityOverride
        ?.takeUnless { showHiddenContent && it == Notification.VISIBILITY_PRIVATE }
    val effective = override
        ?: if (visibility == Notification.VISIBILITY_SECRET) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PUBLIC
    val n = when (effective) {
        Notification.VISIBILITY_SECRET -> return null
        Notification.VISIBILITY_PRIVATE -> {
            val publicTitle = cleanText(publicTitle)
            val publicText = cleanText(publicText)
            AodNotification(
                key = key,
                postTime = postTime,
                title = publicTitle,
                text = publicText,
                messages = emptyList(),
                contentHidden = publicTitle == null && publicText == null,
                contentIntent = contentIntent,
            )
        }
        else -> AodNotification(
            key = key,
            postTime = postTime,
            title = cleanText(title),
            text = cleanText(text),
            messages = messages.mapNotNull { m -> cleanText(m.text)?.let { m.copy(text = it) } },
            contentHidden = false,
            contentIntent = contentIntent,
        )
    }
    val hasContent = !n.title.isNullOrBlank() || !n.text.isNullOrBlank() || n.messages.isNotEmpty()
    return n.takeIf { it.contentHidden || hasContent }
}

object NotificationRepository {
    private val _apps = MutableStateFlow<List<AppNotifications>>(emptyList())
    val apps: StateFlow<List<AppNotifications>> = _apps.asStateFlow()

    fun update(apps: List<AppNotifications>) {
        _apps.value = apps
    }
}
