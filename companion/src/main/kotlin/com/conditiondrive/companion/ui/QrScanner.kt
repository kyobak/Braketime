package com.conditiondrive.companion.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.conditiondrive.companion.pairing.PairingLink
import com.conditiondrive.companion.pairing.QrDecoder
import java.util.concurrent.Executors

/** 연결 화면이 쓰는 스캐너. 시험은 카메라 없이 "스캔 열림" 상태의 뒤로 가기만 보려고 바꿔 끼운다 */
internal val LocalQrScanner = staticCompositionLocalOf<@Composable (onCode: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier) -> Unit> {
    { onCode, onUnavailable, modifier -> QrScanner(onCode, onUnavailable, modifier) }
}

/** 카메라 프레임은 Y 평면만 메모리에서 읽고 저장하지 않는다. 이 앱 형식이 아닌 QR은 없는 것처럼 지나간다 */
@Composable
fun QrScanner(onCode: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCode = rememberUpdatedState(onCode)
    val latestOnUnavailable = rememberUpdatedState(onUnavailable)
    // 4:3 미리보기가 정사각형 칸 밖으로 넘쳐 위 안내 문장을 덮는다. SurfaceView는 Compose 클립을 받지 않아 TextureView 모드로 두고 아래에서 자른다
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val decoder = QrDecoder()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            // 뒤 카메라가 없거나 카메라를 열 수 없으면 get·bindToLifecycle이 던진다. 받지 않으면 앱이 닫혀, 코드 입력으로 돌려보낸다
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    val code = image.use { readCode(it, decoder) }
                    if (code != null) mainExecutor.execute { if (!disposed) latestOnCode.value(code) }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                latestOnUnavailable.value()
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier.clipToBounds())
}

private fun readCode(image: ImageProxy, decoder: QrDecoder): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val luma = ByteArray(buffer.remaining()).also(buffer::get)
    val text = decoder.decode(luma, image.width, image.height, plane.rowStride) ?: return null
    return PairingLink.parse(text)
}
