package im.g.apps.core.bridge.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AndroidMediaPathsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val paths = AndroidMediaPaths(context)
    private val root = File(context.cacheDir, "gim/media")

    @Test
    fun plain_ids_map_to_chat_dir_and_message_file() {
        val media = File(paths.destFor("chat-1", "msg_42", thumbnail = false))
        val thumb = File(paths.destFor("chat-1", "msg_42", thumbnail = true))

        assertEquals(File(root, "chat-1/msg_42"), media)
        assertEquals(File(root, "chat-1/msg_42.thumb"), thumb)
        assertTrue(media.parentFile!!.isDirectory)
    }

    @Test
    fun unsafe_ids_stay_inside_root_and_do_not_collide() {
        val escaped = File(paths.destFor("../../etc", "a/b", thumbnail = false))
        assertTrue(escaped.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertEquals(root.canonicalFile, escaped.parentFile!!.parentFile!!.canonicalFile)

        assertNotEquals(paths.destFor("c", "a/b", false), paths.destFor("c", "a_b", false))
        assertNotEquals(paths.destFor("c", "", false), paths.destFor("c", "_", false))
    }
}
