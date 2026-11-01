package app.astroexposure.camera

import android.util.Range
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The one CameraX adapter behind [CameraViewfinderPort]
 * (ui-design §7.9, 0.17.0; selection record ADR-0005). Every
 * CameraX type in the app lives in this file: the binding
 * (provider + preview use case only — the preview-only
 * construction is what makes "no picture is ever taken or stored"
 * a code fact, pinned by a source-level test), the surface hand-off
 * to the Compose viewfinder, and the red-light exposure handling.
 *
 * The preview fills its bounds with a symmetric centre crop (the
 * viewfinder's crop content scale, passed explicitly): only under
 * a symmetric crop is the preview's centre the camera axis's
 * image, which the aiming semantics rest on (§7.9).
 */
class CameraXViewfinderAdapter : CameraViewfinderPort {

    @Composable
    override fun ViewfinderView(
        redLight: Boolean,
        onFailure: (CameraViewfinderPort.Failure) -> Unit,
        modifier: Modifier,
    ) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
        var control by remember { mutableStateOf<CameraControl?>(null) }
        var exposureRange by remember { mutableStateOf<Range<Int>?>(null) }
        var binding by remember { mutableStateOf<Binding?>(null) }
        val failureCallback by rememberUpdatedState(onFailure)
        var failureReported by remember { mutableStateOf(false) }
        fun report(failure: CameraViewfinderPort.Failure) {
            if (!failureReported) {
                failureReported = true
                failureCallback(failure)
            }
        }

        LaunchedEffect(lifecycleOwner) {
            val provider = try {
                ProcessCameraProvider.getInstance(context)
                    .awaitValue(ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                report(CameraViewfinderPort.Failure.OPEN_FAILED)
                return@LaunchedEffect
            }
            val hasRearCamera = try {
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            } catch (e: Exception) {
                false
            }
            if (!hasRearCamera) {
                report(CameraViewfinderPort.Failure.UNAVAILABLE)
                return@LaunchedEffect
            }
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request -> surfaceRequest = request }
            val camera = try {
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview,
                )
            } catch (e: Exception) {
                report(CameraViewfinderPort.Failure.OPEN_FAILED)
                return@LaunchedEffect
            }
            binding = Binding(provider, preview)
            control = camera.cameraControl
            exposureRange = camera.cameraInfo.exposureState
                .takeIf { it.isExposureCompensationSupported }
                ?.exposureCompensationRange
        }
        DisposableEffect(binding) {
            val bound = binding
            onDispose {
                if (bound != null) {
                    bound.provider.unbind(bound.preview)
                }
            }
        }

        // Red light (§7.9): drive the exposure compensation to the
        // camera's minimum while red light is on; restore the
        // neutral index when it turns off (the app sets no other
        // exposure value anywhere, so neutral is the pre-existing
        // state). Unsupported compensation simply leaves the auto
        // exposure as it is — the tint below still applies.
        var minimumApplied by remember { mutableStateOf(false) }
        LaunchedEffect(redLight, control, exposureRange) {
            val activeControl = control ?: return@LaunchedEffect
            val range = exposureRange
            if (redLight && range != null) {
                activeControl.setExposureCompensationIndex(range.lower)
                minimumApplied = true
            } else if (!redLight && minimumApplied) {
                activeControl.setExposureCompensationIndex(0)
                minimumApplied = false
            }
        }

        Box(modifier = modifier) {
            val request = surfaceRequest
            if (request != null) {
                CameraXViewfinder(
                    surfaceRequest = request,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (redLight) {
                // A multiplicative dimming tint in the red-light
                // primary hue — not a theme-grade dark red (§7.9
                // states the limit plainly).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RED_TINT_COLOR.copy(alpha = RED_TINT_ALPHA)),
                )
            }
        }
    }

    /** The bound pair, kept only so disposal can unbind exactly it. */
    private class Binding(
        val provider: ProcessCameraProvider,
        val preview: Preview,
    )

    private companion object {
        /** The red-light theme's primary hue (§5.1 token, #FF9D8A). */
        val RED_TINT_COLOR = Color(0xFFFF9D8A)

        /**
         * Tint strength for the red-light viewfinder: an empirical
         * starting value inside the design's 0.55–0.65 band, to be
         * tuned on a device (brighter scenes want the upper end).
         */
        const val RED_TINT_ALPHA = 0.60f
    }
}

/** Await a [ListenableFuture] without a coroutines-guava dependency. */
private suspend fun <T> ListenableFuture<T>.awaitValue(
    executor: java.util.concurrent.Executor,
): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (e: ExecutionException) {
                continuation.resumeWithException(e.cause ?: e)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        },
        executor,
    )
    continuation.invokeOnCancellation { cancel(false) }
}
