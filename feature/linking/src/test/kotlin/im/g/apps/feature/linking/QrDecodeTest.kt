package im.g.apps.feature.linking

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecodeTest {
    /** A Y plane with [code] drawn at [scale] pixels per module; rows padded to [rowStride] bytes like camera frames. */
    private fun lumaOf(code: String, scale: Int, rowStride: Int): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
        val width = matrix.width * scale
        val height = matrix.height * scale
        val luma = ByteArray(rowStride * height) { 0x55 }
        for (y in 0 until height) {
            for (x in 0 until width) {
                luma[y * rowStride + x] = if (matrix.get(x / scale, y / scale)) 0 else 0xFF.toByte()
            }
        }
        return Triple(luma, width, height)
    }

    @Test
    fun decodes_a_qr_code_from_a_padded_y_plane() {
        val code = "gimlink:v1:https://hub.example.eu:AbCdEf0123456789-_"
        val (luma, width, height) = lumaOf(code, scale = 4, rowStride = 4 * 64 + 128)

        assertEquals(code, decodeQr(qrReader(), luma, 4 * 64 + 128, width, height))
    }

    @Test
    fun reader_is_reusable_after_a_miss() {
        val reader = qrReader()
        val blank = ByteArray(200 * 200) { 0xFF.toByte() }
        assertNull(decodeQr(reader, blank, 200, 200, 200))

        val (luma, width, height) = lumaOf("second", scale = 3, rowStride = 256)
        assertEquals("second", decodeQr(reader, luma, 256, width, height))
    }
}
