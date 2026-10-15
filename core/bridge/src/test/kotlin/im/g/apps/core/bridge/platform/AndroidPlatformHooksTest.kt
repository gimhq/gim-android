package im.g.apps.core.bridge.platform

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import im.g.apps.core.bridge.GimException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStoreException

@RunWith(RobolectricTestRunner::class)
class AndroidPlatformHooksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var monitor: AndroidReachabilityMonitor
    private lateinit var cipher: FakeSecretCipher
    private lateinit var hooks: AndroidPlatformHooks
    private val secretsDir get() = File(context.noBackupFilesDir, "secrets")

    @Before
    fun setUp() {
        monitor = AndroidReachabilityMonitor(context)
        cipher = FakeSecretCipher()
        hooks = AndroidPlatformHooks(context, cipher, monitor)
    }

    @After
    fun tearDown() {
        monitor.close()
    }

    @Test
    fun secure_store_round_trips_overwrites_and_deletes() {
        assertNull(hooks.secureStoreGet("session"))

        hooks.secureStoreSet("session", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), hooks.secureStoreGet("session"))

        hooks.secureStoreSet("session", byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), hooks.secureStoreGet("session"))

        hooks.secureStoreSet("other", byteArrayOf())
        assertArrayEquals(byteArrayOf(), hooks.secureStoreGet("other"))

        hooks.secureStoreDelete("session")
        assertNull(hooks.secureStoreGet("session"))
        assertArrayEquals(byteArrayOf(), hooks.secureStoreGet("other"))
        hooks.secureStoreDelete("session")
    }

    @Test
    fun secret_files_are_sealed_and_named_by_key_hash() {
        hooks.secureStoreSet("identity/mls-key", "plain-secret".toByteArray())

        val files = secretsDir.listFiles().orEmpty()
        assertEquals(1, files.size)
        val file = files.single()
        assertEquals(sha256Hex("identity/mls-key"), file.name)
        assertTrue(file.name.matches(Regex("[0-9a-f]{64}")))
        assertFalse(file.name.contains("mls"))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("plain-secret"))
    }

    @Test
    fun corrupted_file_reads_as_null_and_is_logged() {
        hooks.secureStoreSet("session", byteArrayOf(1, 2, 3))
        File(secretsDir, sha256Hex("session")).writeBytes(byteArrayOf(0, 0, 0, 0))
        ShadowLog.clear()

        assertNull(hooks.secureStoreGet("session"))
        assertTrue(ShadowLog.getLogsForTag("SecretFileStore").any { it.type == Log.WARN })
    }

    @Test
    fun unavailable_keystore_throws_storage_error_instead_of_null() {
        hooks.secureStoreSet("session", byteArrayOf(1, 2, 3))
        cipher.openFailure = KeyStoreException("keystore locked")
        try {
            hooks.secureStoreGet("session")
            fail("expected GimException.Storage")
        } catch (e: GimException.Storage) {
            assertTrue(e.reason.contains("keystore locked"))
        }
        // A missing key still reads as null without touching the keystore.
        assertNull(hooks.secureStoreGet("absent"))

        cipher.openFailure = null
        assertArrayEquals(byteArrayOf(1, 2, 3), hooks.secureStoreGet("session"))
    }

    @Test
    fun unreadable_file_throws_storage_error() {
        val file = File(secretsDir, sha256Hex("session"))
        file.mkdirs() // a directory where the secret file should be: exists() but readBytes() fails
        try {
            hooks.secureStoreGet("session")
            fail("expected GimException.Storage")
        } catch (_: GimException.Storage) {
        } finally {
            file.delete()
        }
    }

    @Test
    fun write_failure_throws_storage_error() {
        secretsDir.deleteRecursively()
        secretsDir.writeText("not a directory")
        try {
            hooks.secureStoreSet("session", byteArrayOf(1))
            fail("expected GimException.Storage")
        } catch (_: GimException.Storage) {
        } finally {
            secretsDir.delete()
        }
    }

    @Test
    fun data_dir_is_an_absolute_owner_only_dir_under_files_dir() {
        File(context.filesDir, "gim").deleteRecursively()

        val path = hooks.dataDir()
        val dir = File(path)
        assertTrue(dir.isAbsolute)
        assertEquals(File(context.filesDir, "gim").absolutePath, path)
        assertTrue(dir.isDirectory)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())))
        // Stable across launches: a new instance resolves the same existing directory.
        assertEquals(path, AndroidPlatformHooks(context, FakeSecretCipher(), monitor).dataDir())
    }

    @Test
    fun wipe_deletes_secrets_key_and_data_dir_and_is_idempotent() {
        hooks.secureStoreSet("session", byteArrayOf(1, 2, 3))
        hooks.secureStoreSet("identity/mls-key", byteArrayOf(4))
        val dataDir = File(hooks.dataDir())
        File(dataDir, "gim.db").writeText("db")

        hooks.wipe()

        assertFalse(secretsDir.exists())
        assertFalse(dataDir.exists())
        assertEquals(1, cipher.deletedKeys)
        assertNull(hooks.secureStoreGet("session"))

        hooks.wipe()
        assertEquals(2, cipher.deletedKeys)

        // The store and data dir work again after a wipe (a new account in the same process).
        hooks.secureStoreSet("session", byteArrayOf(7))
        assertArrayEquals(byteArrayOf(7), hooks.secureStoreGet("session"))
        assertTrue(File(hooks.dataDir()).isDirectory)
    }

    @Test
    fun network_reachability_reads_the_monitor() {
        assertEquals(monitor.reachable.value, hooks.isNetworkReachable())
    }
}
