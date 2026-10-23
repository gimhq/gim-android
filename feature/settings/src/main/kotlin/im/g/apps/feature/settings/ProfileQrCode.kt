package im.g.apps.feature.settings

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The text the profile QR code encodes; read by device tests, never announced by accessibility services. */
val ProfileQrPayloadKey = SemanticsPropertyKey<String>("ProfileQrPayload")
var SemanticsPropertyReceiver.profileQrPayload by ProfileQrPayloadKey

/** Renders [text] as a QR code (black on white, with the standard quiet zone) using ZXing core. */
@Composable
internal fun ProfileQrCode(text: String, contentDescription: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { encodeQr(text).asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        filterQuality = FilterQuality.None,
        modifier = modifier.semantics { profileQrPayload = text },
    )
}

/** One module per pixel, error correction M. */
internal fun encodeQrMatrix(text: String): BitMatrix {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to QUIET_ZONE)
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
}

/** One pixel per module; the image is scaled up without filtering. */
private fun encodeQr(text: String): Bitmap {
    val matrix = encodeQrMatrix(text)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

private const val QUIET_ZONE = 4
