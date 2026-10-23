package im.g.apps.feature.settings

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileQrCodeTest {
    @Test
    fun the_qr_code_decodes_back_to_the_plain_account_id() {
        val matrix = encodeQrMatrix(ProfileScreenTest.ACCOUNT_ID)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)

        val decoded = QRCodeReader()
            .decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.PURE_BARCODE to true))
            .text

        assertEquals(ProfileScreenTest.ACCOUNT_ID, decoded)
    }
}
