package be.matchreview.app.recording

import androidx.camera.core.Preview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CameraLens { BACK, FRONT }

sealed interface CameraRecordingState {
    data object Idle : CameraRecordingState
    data class Preparing(val matchId: Long) : CameraRecordingState
    data class Recording(
        val segmentId: Long,
        val matchId: Long,
        val startedAtElapsedRealtimeMs: Long,
        val audioEnabled: Boolean
    ) : CameraRecordingState
    data class Finalizing(val segmentId: Long, val matchId: Long) : CameraRecordingState
    data class Error(val message: String) : CameraRecordingState
}

/** The match whose video is being prepared, recorded or saved, if any. */
val CameraRecordingState.activeMatchId: Long?
    get() = when (this) {
        is CameraRecordingState.Preparing -> matchId
        is CameraRecordingState.Recording -> matchId
        is CameraRecordingState.Finalizing -> matchId
        else -> null
    }

val CameraRecordingState.isBusy: Boolean
    get() = activeMatchId != null

object CameraRecordingController {
    private val _state = MutableStateFlow<CameraRecordingState>(CameraRecordingState.Idle)
    val state: StateFlow<CameraRecordingState> = _state.asStateFlow()

    private val _lens = MutableStateFlow(CameraLens.BACK)
    val lens: StateFlow<CameraLens> = _lens.asStateFlow()

    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()

    @Volatile private var previewConsumer: ((Preview.SurfaceProvider?) -> Unit)? = null
    @Volatile private var currentSurfaceProvider: Preview.SurfaceProvider? = null
    @Volatile private var torchConsumer: ((Boolean) -> Unit)? = null

    internal fun updateState(value: CameraRecordingState) { _state.value = value }
    internal fun updateLens(value: CameraLens) { _lens.value = value }
    internal fun updateTorch(value: Boolean) { _torchEnabled.value = value }
    internal fun setPreviewConsumer(value: ((Preview.SurfaceProvider?) -> Unit)?) {
        previewConsumer = value
        value?.invoke(currentSurfaceProvider)
    }
    internal fun setTorchConsumer(value: ((Boolean) -> Unit)?) {
        torchConsumer = value
    }

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = surfaceProvider
        previewConsumer?.invoke(surfaceProvider)
    }

    fun chooseLens(value: CameraLens) {
        if (_state.value is CameraRecordingState.Idle || _state.value is CameraRecordingState.Error) {
            updateLens(value)
        }
    }

    fun setTorch(enabled: Boolean) {
        torchConsumer?.invoke(enabled)
    }
}
