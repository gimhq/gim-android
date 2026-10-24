package im.g.apps.feature.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Longest side of an own profile photo; every member of every chat downloads it. */
private const val MAX_SIDE = 512

private const val JPEG_QUALITY = 85

/**
 * Profile photo files in the app cache: downloads a user's photo into `gim/avatars/` and turns a picked picture into a
 * small square JPEG ready for [GimClient.setAvatar]. Blocking; call off the main thread.
 */
class ProfilePhotos(private val client: GimClient, cacheDir: File) {
    private val avatarDir = File(cacheDir, "gim/avatars")
    private val uploadFile = File(cacheDir, "gim/profile-photo.jpg")

    /** The local path of [userId]'s photo; null when they have none or it can't be downloaded now. */
    suspend fun load(userId: String): String? = try {
        client.fetchUserAvatar(userId, File(avatarDir, userId).path)
    } catch (_: GimException) {
        null
    }

    /**
     * Crops the picture [open] reads to a centred square, scales it to at most 512 x 512 and writes it as a JPEG.
     * [open] is called more than once. Null when it can't be read as a picture.
     */
    fun prepare(open: () -> InputStream?): File? = try {
        decodeSquare(open)?.let { square ->
            uploadFile.parentFile?.mkdirs()
            uploadFile.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            square.recycle()
            uploadFile
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun decodeSquare(open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Only fills in the bounds; decodeStream returns null here.
        (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode at the largest power-of-two reduction that still covers MAX_SIDE, so a camera photo fits in memory.
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val rotation = open()?.use { orientationDegrees(it) } ?: 0

        val side = minOf(decoded.width, decoded.height)
        val target = minOf(side, MAX_SIDE)
        val matrix = Matrix().apply {
            postScale(target.toFloat() / side, target.toFloat() / side)
            postRotate(rotation.toFloat())
        }
        val square = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side,
            matrix,
            true,
        )
        if (square !== decoded) decoded.recycle()
        return square
    }

    /** The rotation the camera wrote into the EXIF data; BitmapFactory ignores it. */
    private fun orientationDegrees(input: InputStream): Int = try {
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: IOException) {
        0
    }
}
