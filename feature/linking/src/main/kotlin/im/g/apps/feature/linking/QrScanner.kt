package im.g.apps.feature.linking

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Back camera preview that reports every decoded QR code to [onQrCode] on the main thread; [onUnavailable] runs
 * when no camera can be bound (for example, an emulator without one). Requires the CAMERA permission.
 */
@Composable
internal fun QrScanner(onQrCode: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQrCode by rememberUpdatedState(onQrCode)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val previewView = remember(context) { PreviewView(context) }
    DisposableEffect(lifecycleOwner, previewView) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, QrCodeAnalyzer { text -> mainExecutor.execute { currentOnQrCode(text) } }) }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        providerFuture.addListener(
            {
                if (disposed) return@addListener
                try {
                    provider = providerFuture.get().also {
                        it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                } catch (e: Exception) {
                    // No back camera, or the camera service failed; the paste fallback stays available.
                    Log.w(TAG, "camera unavailable", e)
                    currentOnUnavailable()
                }
            },
            mainExecutor,
        )
        onDispose {
            disposed = true
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            analysisExecutor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Decodes QR codes from the luminance (Y) plane of each YUV_420_888 frame. */
internal class QrCodeAnalyzer(private val onQrCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = qrReader()

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val buffer = plane.buffer
            val luma = ByteArray(buffer.remaining()).also(buffer::get)
            decodeQr(reader, luma, plane.rowStride, it.width, it.height)?.let(onQrCode)
        }
    }
}

/** A reader restricted to QR codes. */
internal fun qrReader(): MultiFormatReader = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
}

/** The text of a QR code in the [width] x [height] luminance image [luma] (rows [rowStride] bytes apart), or null. */
internal fun decodeQr(reader: MultiFormatReader, luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
    val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}

private const val TAG = "QrScanner"
