package im.g.apps.feature.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.fake.FakeGimClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePhotoTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun TestScope.signedIn(): FakeGimClient =
        FakeGimClient(backgroundScope, outboxDelays = null).apply { createIdentity("Sofia Costa") }

    private fun TestScope.viewModel(client: FakeGimClient) =
        ProfileViewModel(client, ProfilePhotos(client, tmp.root), ioDispatcher = main.dispatcher).also { advanceUntilIdle() }

    private fun png(width: Int, height: Int): () -> InputStream {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return { ByteArrayInputStream(bytes) }
    }

    @Test
    fun prepare_crops_a_centred_square_and_scales_it_to_512_jpeg() {
        val photos = ProfilePhotos(FakeGimClient(TestScope()), tmp.root)

        val file = photos.prepare(png(1600, 900))!!

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        assertEquals(512, options.outWidth)
        assertEquals(512, options.outHeight)
        assertEquals("image/jpeg", options.outMimeType)
        assertTrue(file.path.startsWith(tmp.root.path))
    }

    @Test
    fun prepare_keeps_a_small_picture_small() {
        val photos = ProfilePhotos(FakeGimClient(TestScope()), tmp.root)

        val file = photos.prepare(png(300, 200))!!

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        assertEquals(200, options.outWidth)
        assertEquals(200, options.outHeight)
    }

    @Test
    fun prepare_returns_null_for_something_that_is_not_a_picture() {
        val photos = ProfilePhotos(FakeGimClient(TestScope()), tmp.root)

        assertNull(photos.prepare { ByteArrayInputStream("not a picture".toByteArray()) })
        assertNull(photos.prepare { null })
    }

    @Test
    fun no_photo_shows_initials() = runTest {
        val state = viewModel(signedIn()).state.value

        assertFalse(state.hasPhoto)
        assertNull(state.photoPath)
    }

    @Test
    fun change_photo_uploads_a_jpeg_and_shows_it() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        vm.changePhoto(png(800, 600))
        assertTrue(vm.state.value.photoSaving)
        advanceUntilIdle()

        assertTrue(client.ownProfile()!!.hasAvatar)
        val state = vm.state.value
        assertFalse(state.photoSaving)
        assertTrue(state.hasPhoto)
        assertNotNull(state.photoPath)
        assertEquals(File(tmp.root, "gim/avatars").path, File(state.photoPath!!).parent)
        assertEquals(ProfileEvent.Message(R.string.profile_photo_saved), vm.events.first())
    }

    @Test
    fun a_picture_that_cannot_be_read_is_not_uploaded() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        vm.changePhoto { ByteArrayInputStream("not a picture".toByteArray()) }
        advanceUntilIdle()

        assertFalse(client.ownProfile()!!.hasAvatar)
        assertFalse(vm.state.value.photoSaving)
        assertEquals(ProfileEvent.Message(R.string.profile_photo_error_unreadable), vm.events.first())
    }

    @Test
    fun a_failed_upload_says_what_to_try() = runTest {
        val client = signedIn()
        val vm = viewModel(client)
        client.failNext("setAvatar", GimException.Network("offline"))

        vm.changePhoto(png(100, 100))
        advanceUntilIdle()

        assertFalse(vm.state.value.hasPhoto)
        assertFalse(vm.state.value.photoSaving)
        assertEquals(ProfileEvent.Message(R.string.profile_photo_error_network), vm.events.first())
    }

    @Test
    fun remove_photo_goes_back_to_initials() = runTest {
        val client = signedIn()
        val vm = viewModel(client)
        vm.changePhoto(png(100, 100))
        advanceUntilIdle()
        vm.events.first()

        vm.removePhoto()
        advanceUntilIdle()

        assertFalse(client.ownProfile()!!.hasAvatar)
        assertFalse(vm.state.value.hasPhoto)
        assertNull(vm.state.value.photoPath)
        assertEquals(ProfileEvent.Message(R.string.profile_photo_removed), vm.events.first())
    }

    @Test
    fun a_failed_remove_keeps_the_photo() = runTest {
        val client = signedIn()
        val vm = viewModel(client)
        vm.changePhoto(png(100, 100))
        advanceUntilIdle()
        vm.events.first()
        client.failNext("removeAvatar", GimException.Storage("disk"))

        vm.removePhoto()
        advanceUntilIdle()

        assertTrue(vm.state.value.hasPhoto)
        assertEquals(ProfileEvent.Message(R.string.profile_photo_error_unknown), vm.events.first())
    }

    @Test
    fun a_photo_that_cannot_be_downloaded_falls_back_to_initials_but_can_be_removed() = runTest {
        val client = signedIn()
        client.setAvatar(File(tmp.root, "a.jpg").apply { writeBytes(byteArrayOf(1)) }.path, "image/jpeg")
        client.failNext("fetchUserAvatar", GimException.Network("offline"))

        val state = viewModel(client).state.value

        assertTrue(state.hasPhoto)
        assertNull(state.photoPath)
    }
}
