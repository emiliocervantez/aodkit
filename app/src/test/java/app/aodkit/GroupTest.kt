package app.aodkit

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTest {

    private fun raw(
        key: String,
        pkg: String = "com.slack",
        postTime: Long = 1,
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
        visibility: Int = Notification.VISIBILITY_PRIVATE, // Android's default
        userOverride: Int? = null,
        title: String? = "Title $key",
        text: String? = "Text $key",
        messages: List<AodMessage> = emptyList(),
        publicTitle: String? = null,
        publicText: String? = null,
    ) = RawNotification(
        key, pkg, postTime, ongoing, groupSummary, visibility, userOverride,
        title, text, messages, publicTitle, publicText, contentIntent = null,
    )

    @Test
    fun `groups per app with newest notification first and newest app first`() {
        val result = group(
            listOf(
                raw("a1", pkg = "a", postTime = 10),
                raw("b1", pkg = "b", postTime = 30),
                raw("a2", pkg = "a", postTime = 20),
            ),
            hiddenApps = emptySet(),
        )
        assertEquals(listOf("b", "a"), result.map { it.packageName })
        assertEquals(listOf("a2", "a1"), result[1].notifications.map { it.key })
    }

    @Test
    fun `skips ongoing, group summaries and hidden apps`() {
        val result = group(
            listOf(
                raw("ongoing", ongoing = true),
                raw("summary", groupSummary = true),
                raw("hidden", pkg = "com.hidden"),
                raw("kept"),
            ),
            hiddenApps = setOf("com.hidden"),
        )
        assertEquals(listOf("kept"), result.flatMap { it.notifications }.map { it.key })
    }

    @Test
    fun `app-default private notification is shown in full`() {
        val n = group(listOf(raw("p", messages = listOf(AodMessage("Ann", "hi")))), emptySet())
            .single().notifications.single()
        assertEquals(false, n.contentHidden)
        assertEquals("Text p", n.text)
        assertEquals(listOf(AodMessage("Ann", "hi")), n.messages)
    }

    @Test
    fun `secret notifications are not shown, whether set by the app or the user`() {
        val result = group(
            listOf(
                raw("app", visibility = Notification.VISIBILITY_SECRET),
                raw("user", userOverride = Notification.VISIBILITY_SECRET),
            ),
            emptySet(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `user override public wins over app secret`() {
        val result = group(
            listOf(raw("s", visibility = Notification.VISIBILITY_SECRET, userOverride = Notification.VISIBILITY_PUBLIC)),
            emptySet(),
        )
        assertEquals("Text s", result.single().notifications.single().text)
    }

    @Test
    fun `user override private without public version is content hidden`() {
        val n = group(
            listOf(raw("p", userOverride = Notification.VISIBILITY_PRIVATE, messages = listOf(AodMessage("Ann", "hi")))),
            emptySet(),
        ).single().notifications.single()
        assertTrue(n.contentHidden)
        assertEquals(null, n.title)
        assertEquals(null, n.text)
        assertTrue(n.messages.isEmpty())
    }

    @Test
    fun `user override private shows only the public version`() {
        val n = group(
            listOf(raw("p", userOverride = Notification.VISIBILITY_PRIVATE, publicTitle = "1 new message")),
            emptySet(),
        ).single().notifications.single()
        assertEquals("1 new message", n.title)
        assertEquals(null, n.text)
        assertEquals(false, n.contentHidden)
    }

    @Test
    fun `showHiddenContent ignores a hide-content override but not secret`() {
        val result = group(
            listOf(
                raw("p", userOverride = Notification.VISIBILITY_PRIVATE),
                raw("s", userOverride = Notification.VISIBILITY_SECRET),
                raw("a", visibility = Notification.VISIBILITY_SECRET),
            ),
            hiddenApps = emptySet(),
            showHiddenContent = true,
        )
        val n = result.single().notifications.single()
        assertEquals("p", n.key)
        assertEquals(false, n.contentHidden)
        assertEquals("Text p", n.text)
    }

    @Test
    fun `public notification without any content is dropped`() {
        val result = group(listOf(raw("empty", title = null, text = " ")), emptySet())
        assertTrue(result.isEmpty())
    }
}
