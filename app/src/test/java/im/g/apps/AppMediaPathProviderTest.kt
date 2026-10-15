package im.g.apps

import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.MediaRef
import im.g.apps.core.bridge.platform.AndroidMediaPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppMediaPathProviderTest {
    private val paths = AndroidMediaPaths(ApplicationProvider.getApplicationContext())
    private val provider = AppMediaPathProvider(paths)
    private val chatDir = File(paths.root, "chat-1").canonicalFile

    private fun media(mime: String = "application/pdf") = MediaRef("chat-1", "msg-1", mime, 10, hasThumbnail = false)

    @Test
    fun traversal_file_name_stays_inside_the_chat_media_dir() {
        val file = File(provider.mediaPath(media("text/xml"), "../../shared_prefs/x.xml"))

        assertEquals(chatDir, file.canonicalFile.parentFile)
        assertEquals("msg-1_x.xml", file.name)
    }

    @Test
    fun keeps_the_original_name_and_extension() {
        assertEquals("msg-1_survey-2026.pdf", File(provider.mediaPath(media(), "survey-2026.pdf")).name)
    }

    @Test
    fun missing_name_falls_back_to_an_extension_from_the_mime_type() {
        // Robolectric's MimeTypeMap starts empty; devices know this mapping.
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("pdf", "application/pdf")

        assertEquals("msg-1.pdf", File(provider.mediaPath(media(), null)).name)
        assertEquals("msg-1_notes.pdf", File(provider.mediaPath(media(), "notes")).name)
    }

    @Test
    fun thumbnails_use_the_id_based_path() {
        assertEquals(paths.destFor("chat-1", "msg-1", thumbnail = true), provider.thumbnailPath(media()))
    }

    @Test
    fun sanitizes_separators_dot_segments_control_chars_and_length() {
        val sanitize = AppMediaPathProvider::sanitizeDisplayName
        assertEquals("x.xml", sanitize("..\\..\\x.xml"))
        assertEquals("a.b", sanitize("a..b"))
        assertEquals("evil.pdf", sanitize("ev\u0000il\n.pdf"))
        assertEquals("Ana_Kovač.pdf", sanitize("Ana*Kovač.pdf"))
        assertEquals("", sanitize(".."))
        assertEquals("", sanitize(null))

        val long = sanitize("a".repeat(200) + ".docx")
        assertEquals(64, long.length)
        assertTrue(long.endsWith(".docx"))
        assertFalse(long.contains('/'))
    }
}
