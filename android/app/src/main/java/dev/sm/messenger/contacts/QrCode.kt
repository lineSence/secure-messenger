package dev.sm.messenger.contacts

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors

/**
 * QR для добавления контакта (ADR-62 п.1): ZXing `core` — и показ, и распознавание; камера —
 * CameraX. Без Google Play services и ML Kit. Кадры камеры никуда не сохраняются.
 */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encodeQr(text) }
    Canvas(
        modifier = modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = "Мой QR для добавления контакта" },
    ) {
        drawRect(Color.White)
        val n = matrix.width
        val cell = size.width / n
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Строка `SM1:` + HEX кодируется буквенно-цифровым режимом; запас на ошибки — M (15 %). */
private fun encodeQr(text: String): BitMatrix =
    QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to QUIET_ZONE),
    )

/**
 * Камера с распознаванием QR. [onText] — в главном потоке, для каждого прочитанного кода
 * (проверяет ядро: чужой QR отклоняется). Камера отпускается, когда экран уходит.
 */
@Composable
fun QrScanner(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    val provider = remember { ProcessCameraProvider.getInstance(context) }
    Box(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    provider.addListener({
                        val p = provider.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor, QrAnalyzer { text -> main.post { onText(text) } })
                        p.unbindAll()
                        p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }, ctx.mainExecutor)
                }
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            if (provider.isDone) provider.get().unbindAll()
            executor.shutdown()
        }
    }
}

/** Яркость (плоскость Y) кадра → ZXing. */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            onText(reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text)
        } catch (_: ReaderException) {
            // В кадре нет QR — ждём следующий.
        } catch (_: IllegalArgumentException) {
            // Размеры кадра не сошлись — пропускаем кадр.
        } finally {
            reader.reset()
            image.close()
        }
    }
}

private const val QUIET_ZONE = 2
