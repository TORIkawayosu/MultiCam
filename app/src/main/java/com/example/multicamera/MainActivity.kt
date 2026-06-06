package com.example.multicamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.os.Environment
import android.util.SizeF
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.multicamera.ui.theme.MultiCameraTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan

class MainActivity : ComponentActivity() {
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraProviderRequested = false
    private var usingCamera2Fallback = false
    private var permissionsGranted = false
    private var ultraPreviewContainer: FrameLayout? = null
    private var widePreviewContainer: FrameLayout? = null
    private var cameraSession: CameraSession? = null
    private var activeRecordings: ActiveRecordings? = null
    private var camera2Recorder: Camera2DualRecorder? = null

    private var uiState by mutableStateOf(MultiCamUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MultiCameraTheme(dynamicColor = false) {
                MultiCamScreen(
                    state = uiState,
                    onPermissionsChanged = ::onPermissionsChanged,
                    onUltraPreviewReady = { container ->
                        ultraPreviewContainer = container
                        bindConcurrentCameras()
                    },
                    onWidePreviewReady = { container ->
                        widePreviewContainer = container
                        bindConcurrentCameras()
                    },
                    onRecordToggle = {
                        if (uiState.isRecording) stopRecording() else startRecording()
                    },
                    onSettingsChanged = { settings ->
                        uiState = uiState.copy(videoSettings = settings)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        activeRecordings?.stopAll()
        cameraProvider?.unbindAll()
        camera2Recorder?.release()
        super.onDestroy()
    }

    private fun onPermissionsChanged(granted: Boolean) {
        permissionsGranted = granted
        uiState = uiState.copy(
            hasPermissions = granted,
            status = if (granted) "Preparazione camere" else "Servono camera e microfono"
        )
        if (granted) requestCameraProvider()
    }

    private fun requestCameraProvider() {
        if (cameraProviderRequested) {
            bindConcurrentCameras()
            return
        }
        cameraProviderRequested = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                runCatching {
                    cameraProvider = future.get()
                    bindConcurrentCameras()
                }.onFailure { error ->
                    uiState = uiState.copy(
                        isCameraReady = false,
                        status = "Camera non disponibile",
                        error = error.localizedMessage ?: "Impossibile inizializzare CameraX"
                    )
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindConcurrentCameras() {
        if (usingCamera2Fallback) return
        val provider = cameraProvider ?: return
        val ultraContainer = ultraPreviewContainer ?: return
        val wideContainer = widePreviewContainer ?: return
        if (!permissionsGranted || activeRecordings != null) return

        runCatching {
            provider.unbindAll()
            val pair = chooseRearConcurrentPair(provider)
                ?: error("Il dispositivo non espone una coppia posteriore 0.5x + 1x utilizzabile in concurrent mode.")

            val ultraRecorder = Recorder.Builder().setQualitySelector(VideoQualitySelector).build()
            val wideRecorder = Recorder.Builder().setQualitySelector(VideoQualitySelector).build()
            val ultraVideo = VideoCapture.withOutput(ultraRecorder)
            val wideVideo = VideoCapture.withOutput(wideRecorder)
            val ultraView = attachCameraXPreview(ultraContainer)
            val wideView = attachCameraXPreview(wideContainer)

            val ultraPreview = Preview.Builder().build().apply { setSurfaceProvider(ultraView.surfaceProvider) }
            val widePreview = Preview.Builder().build().apply { setSurfaceProvider(wideView.surfaceProvider) }

            val ultraConfig = ConcurrentCamera.SingleCameraConfig(
                selectorForCameraId(pair.ultra.cameraId),
                UseCaseGroup.Builder().addUseCase(ultraPreview).addUseCase(ultraVideo).build(),
                this
            )
            val wideConfig = ConcurrentCamera.SingleCameraConfig(
                selectorForCameraId(pair.wide.cameraId),
                UseCaseGroup.Builder().addUseCase(widePreview).addUseCase(wideVideo).build(),
                this
            )

            provider.bindToLifecycle(listOf(ultraConfig, wideConfig))
            cameraSession = CameraSession(ultra = BoundLens(pair.ultra, ultraVideo), wide = BoundLens(pair.wide, wideVideo))
            uiState = uiState.copy(
                isCameraReady = true,
                status = "Pronto",
                error = null,
                ultraCameraLabel = "0.5x",
                wideCameraLabel = "1x",
                ultraCameraDetails = pair.ultra.detailLabel(),
                wideCameraDetails = pair.wide.detailLabel()
            )
        }.onFailure { error ->
            cameraSession = null
            startCamera2Fallback(error.localizedMessage ?: "Associazione CameraX fallita")
        }
    }

    private fun attachCameraXPreview(container: FrameLayout): PreviewView {
        container.removeAllViews()
        return PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            container.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun startCamera2Fallback(reason: String) {
        val ultraContainer = ultraPreviewContainer ?: return
        val wideContainer = widePreviewContainer ?: return
        usingCamera2Fallback = true
        cameraProvider?.unbindAll()
        uiState = uiState.copy(
            isCameraReady = false,
            status = "Provo fallback Camera2",
            error = "CameraX concurrent non disponibile: $reason"
        )

        camera2Recorder?.release()
        camera2Recorder = Camera2DualRecorder(
            context = this,
            ultraContainer = ultraContainer,
            wideContainer = wideContainer,
            callback = object : Camera2DualRecorder.Callback {
                override fun onCamera2Ready(status: String, ultraDetails: String, wideDetails: String) {
                    runOnUiThread {
                        uiState = uiState.copy(
                            isCameraReady = true,
                            status = status,
                            error = null,
                            ultraCameraLabel = "0.5x",
                            wideCameraLabel = "1x",
                            ultraCameraDetails = ultraDetails,
                            wideCameraDetails = wideDetails
                        )
                    }
                }
                override fun onCamera2Error(message: String, diagnostics: String) {
                    runOnUiThread {
                        uiState = uiState.copy(
                            isCameraReady = false,
                            isRecording = false,
                            status = "Camera2 non disponibile",
                            error = "$message\n$diagnostics"
                        )
                    }
                }
                override fun onCamera2RecordingStarted(status: String) {
                    runOnUiThread {
                        uiState = uiState.copy(
                            isRecording = true,
                            status = status,
                            error = null,
                            lastFiles = emptyList()
                        )
                    }
                }
                override fun onCamera2RecordingFinished(files: List<String>, message: String) {
                    runOnUiThread {
                        uiState = uiState.copy(
                            isRecording = false,
                            isCameraReady = true,
                            status = message,
                            error = null,
                            lastFiles = files
                        )
                    }
                }
            }
        ).also { it.startPreview() }
    }

    private fun startRecording() {
        if (usingCamera2Fallback) {
            if (!permissionsGranted) return
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            camera2Recorder?.startRecording(recordingDirectory(), timestamp, uiState.videoSettings)
            return
        }
        startCameraXRecording()
    }

    @SuppressLint("MissingPermission")
    private fun startCameraXRecording() {
        val session = cameraSession ?: return
        if (!permissionsGranted || activeRecordings != null) return

        val outputDir = recordingDirectory()
        outputDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ultraFile = File(outputDir, "MultiCam_${timestamp}_0_5x.mp4")
        val wideFile = File(outputDir, "MultiCam_${timestamp}_1x.mp4")

        runCatching {
            val ultraRecording = session.ultra.videoCapture.output
                .prepareRecording(this, FileOutputOptions.Builder(ultraFile).build())
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event -> onVideoEvent("0.5x", ultraFile, event) }

            val wideRecording = session.wide.videoCapture.output
                .prepareRecording(this, FileOutputOptions.Builder(wideFile).build())
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event -> onVideoEvent("1x", wideFile, event) }

            activeRecordings = ActiveRecordings(ultra = ultraRecording, wide = wideRecording, files = listOf(ultraFile, wideFile))
            uiState = uiState.copy(isRecording = true, status = "Registrazione in corso", error = null, lastFiles = emptyList())
        }.onFailure { error ->
            activeRecordings?.stopAll()
            activeRecordings = null
            uiState = uiState.copy(
                isRecording = false,
                status = "Registrazione non avviata",
                error = error.localizedMessage ?: "Errore durante l'avvio della registrazione"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopRecording() {
        if (usingCamera2Fallback) {
            uiState = uiState.copy(status = "Salvataggio video")
            camera2Recorder?.stopRecording()
            return
        }
        val recordings = activeRecordings ?: return
        uiState = uiState.copy(status = "Salvataggio video")
        recordings.stopAll()
    }

    private fun onVideoEvent(label: String, file: File, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                uiState = uiState.copy(status = "Registrazione $label avviata")
            }
            is VideoRecordEvent.Finalize -> {
                val recordings = activeRecordings
                recordings?.finalizedLabels?.add(label)
                val nextError = if (event.hasError()) {
                    "$label: ${event.cause?.localizedMessage ?: "registrazione interrotta"}"
                } else {
                    uiState.error
                }
                if (recordings == null || recordings.finalizedLabels.size >= 2) {
                    activeRecordings = null
                    bindConcurrentCameras()
                    val exportedFiles = recordings?.files
                        ?.takeIf { nextError == null }
                        ?.let { MediaStoreVideoExporter.export(this, it) }
                        .orEmpty()
                    uiState = uiState.copy(
                        isRecording = false,
                        status = if (nextError == null) "Video salvati in Movies/MultiCam" else "Registrazione completata con errori",
                        error = nextError,
                        lastFiles = exportedFiles.ifEmpty {
                            recordings?.files?.map { it.absolutePath } ?: listOf(file.absolutePath)
                        }
                    )
                } else if (nextError != null) {
                    uiState = uiState.copy(error = nextError)
                }
            }
            else -> Unit
        }
    }

    private fun recordingDirectory(): File {
        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        return File(moviesDir, "MultiCam")
    }

    private fun chooseRearConcurrentPair(provider: ProcessCameraProvider): RearLensPair? {
        return provider.availableConcurrentCameraInfos
            .asSequence()
            .mapNotNull { infos ->
                val rearInfos = infos
                    .filter { it.lensFacing() == CameraCharacteristics.LENS_FACING_BACK }
                    .map { it.toLensDescriptor() }
                    .sortedByDescending { it.horizontalFovDegrees }
                if (rearInfos.size >= 2) {
                    RearLensPair(
                        ultra = rearInfos[0],
                        wide = rearInfos[1],
                        fovGap = rearInfos[0].horizontalFovDegrees - rearInfos[1].horizontalFovDegrees
                    )
                } else null
            }
            .maxWithOrNull(compareBy<RearLensPair> { it.fovGap }.thenBy { it.ultra.horizontalFovDegrees })
    }

    private fun selectorForCameraId(cameraId: String): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
            }
            .build()
    }

    private fun CameraInfo.toLensDescriptor(): LensDescriptor {
        val camera2Info = Camera2CameraInfo.from(this)
        val focalLengths = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val minFocalLength = focalLengths?.minOrNull()
        return LensDescriptor(
            cameraId = camera2Info.cameraId,
            focalLengthMm = minFocalLength,
            horizontalFovDegrees = horizontalFov(sensorSize, minFocalLength)
        )
    }

    private fun CameraInfo.lensFacing(): Int? {
        return Camera2CameraInfo.from(this).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
    }

    private fun horizontalFov(sensorSize: SizeF?, focalLengthMm: Float?): Float {
        if (sensorSize == null || focalLengthMm == null || focalLengthMm <= 0f) return 0f
        return Math.toDegrees(2.0 * atan(sensorSize.width.toDouble() / (2.0 * focalLengthMm))).toFloat()
    }

    private companion object {
        val VideoQualitySelector: QualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
    }
}

// ── UI State ──────────────────────────────────────────────────────────────────

private data class MultiCamUiState(
    val hasPermissions: Boolean = false,
    val isCameraReady: Boolean = false,
    val isRecording: Boolean = false,
    val status: String = "In attesa dei permessi",
    val error: String? = null,
    val ultraCameraLabel: String = "0.5x",
    val wideCameraLabel: String = "1x",
    val ultraCameraDetails: String = "Ultra-wide",
    val wideCameraDetails: String = "Wide",
    val lastFiles: List<String> = emptyList(),
    val videoSettings: DualVideoSettings = DualVideoSettings()
)

// ── Camera descriptors ────────────────────────────────────────────────────────

private data class LensDescriptor(
    val cameraId: String,
    val focalLengthMm: Float?,
    val horizontalFovDegrees: Float
) {
    fun detailLabel(): String {
        val focal = focalLengthMm?.let { "%.1f mm".format(Locale.US, it) } ?: "focale n/d"
        val fov = if (horizontalFovDegrees > 0f) {
            "%.0f deg".format(Locale.US, horizontalFovDegrees)
        } else {
            "FOV n/d"
        }
        return "ID $cameraId - $focal - $fov"
    }
}

private data class RearLensPair(val ultra: LensDescriptor, val wide: LensDescriptor, val fovGap: Float)
private data class BoundLens(val descriptor: LensDescriptor, val videoCapture: VideoCapture<Recorder>)
private data class CameraSession(val ultra: BoundLens, val wide: BoundLens)
private data class ActiveRecordings(
    val ultra: Recording,
    val wide: Recording,
    val files: List<File>,
    val finalizedLabels: MutableSet<String> = linkedSetOf()
) {
    fun stopAll() { ultra.stop(); wide.stop() }
}

// ── Composables ───────────────────────────────────────────────────────────────

@Composable
private fun MultiCamScreen(
    state: MultiCamUiState,
    onPermissionsChanged: (Boolean) -> Unit,
    onUltraPreviewReady: (FrameLayout) -> Unit,
    onWidePreviewReady: (FrameLayout) -> Unit,
    onRecordToggle: () -> Unit,
    onSettingsChanged: (DualVideoSettings) -> Unit
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onPermissionsChanged(
            results[Manifest.permission.CAMERA] == true &&
                results[Manifest.permission.RECORD_AUDIO] == true
        )
    }

    LaunchedEffect(Unit) {
        val granted = RequiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) onPermissionsChanged(true)
        else permissionLauncher.launch(RequiredPermissions)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF111315)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF171A1E), Color(0xFF0C0D0F))))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Header(state)
                PreviewLayout(
                    state = state,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onUltraPreviewReady = onUltraPreviewReady,
                    onWidePreviewReady = onWidePreviewReady
                )
                SettingsPanel(
                    settings = state.videoSettings,
                    enabled = !state.isRecording,
                    onSettingsChanged = onSettingsChanged
                )
                ControlDock(state = state, onRecordToggle = onRecordToggle)
            }
        }
    }
}

@Composable
private fun Header(state: MultiCamUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "MultiCam",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = state.status,
                color = if (state.error == null) Color(0xFFB7C3C9) else Color(0xFFFFB3A9),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        StatusPill(
            text = if (state.isRecording) "REC" else if (state.isCameraReady) "READY" else "WAIT",
            color = if (state.isRecording) Color(0xFFE94141) else if (state.isCameraReady) Color(0xFF35D07F) else Color(0xFFFFC857)
        )
    }
}

@Composable
private fun PreviewLayout(
    state: MultiCamUiState,
    modifier: Modifier = Modifier,
    onUltraPreviewReady: (FrameLayout) -> Unit,
    onWidePreviewReady: (FrameLayout) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val horizontal = maxWidth > maxHeight
        if (horizontal) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewTile(
                    title = state.ultraCameraLabel, subtitle = state.ultraCameraDetails,
                    recording = state.isRecording,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onPreviewReady = onUltraPreviewReady
                )
                PreviewTile(
                    title = state.wideCameraLabel, subtitle = state.wideCameraDetails,
                    recording = state.isRecording,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onPreviewReady = onWidePreviewReady
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewTile(
                    title = state.ultraCameraLabel, subtitle = state.ultraCameraDetails,
                    recording = state.isRecording,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onPreviewReady = onUltraPreviewReady
                )
                PreviewTile(
                    title = state.wideCameraLabel, subtitle = state.wideCameraDetails,
                    recording = state.isRecording,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onPreviewReady = onWidePreviewReady
                )
            }
        }
    }
}

@Composable
private fun PreviewTile(
    title: String,
    subtitle: String,
    recording: Boolean,
    modifier: Modifier = Modifier,
    onPreviewReady: (FrameLayout) -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF090A0C)),
        border = BorderStroke(1.dp, if (recording) Color(0xFFE94141) else Color(0xFF2C343A))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).also { frame ->
                        frame.layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                        onPreviewReady(frame)
                    }
                }
            )
            // Label overlay
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC111315))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = Color(0xFFBFE7FF),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(text = subtitle, color = Color(0xFFC9D3D8), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // REC badge
            if (recording) {
                StatusPill(
                    text = "REC",
                    color = Color(0xFFE94141),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                )
            }
            // "Preview off during REC" overlay
            if (recording) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xBB111315))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Preview disabilitata\ndurante la registrazione",
                        color = Color(0xFFB7C3C9),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

// ── Settings panel ────────────────────────────────────────────────────────────

@Composable
private fun SettingsPanel(
    settings: DualVideoSettings,
    enabled: Boolean,
    onSettingsChanged: (DualVideoSettings) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B1F)),
        border = BorderStroke(1.dp, Color(0xFF2C343A))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 0.5x row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "0.5x",
                    color = Color(0xFFBFE7FF),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(38.dp)
                )
                QualitySelector(
                    selected = settings.ultraQuality,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { q -> onSettingsChanged(settings.copy(ultraQuality = q)) }
                Spacer(Modifier.width(4.dp))
                FpsSelector(
                    selected = settings.ultraFps,
                    enabled = enabled
                ) { f -> onSettingsChanged(settings.copy(ultraFps = f)) }
            }
            // 1x row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "1x",
                    color = Color(0xFF8EE7B5),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(38.dp)
                )
                QualitySelector(
                    selected = settings.wideQuality,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { q -> onSettingsChanged(settings.copy(wideQuality = q)) }
                Spacer(Modifier.width(4.dp))
                FpsSelector(
                    selected = settings.wideFps,
                    enabled = enabled
                ) { f -> onSettingsChanged(settings.copy(wideFps = f)) }
            }
        }
    }
}

@Composable
private fun QualitySelector(
    selected: QualityPreference,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (QualityPreference) -> Unit
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QualityPreference.entries.forEach { q ->
            val active = q == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) Color(0xFF35D07F).copy(alpha = 0.18f) else Color(0xFF1E262B))
                    .border(1.dp, if (active) Color(0xFF35D07F) else Color(0xFF2C343A), RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { onSelect(q) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = q.label,
                    color = if (active) Color(0xFF35D07F) else if (enabled) Color(0xFFB7C3C9) else Color(0xFF4A5560),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun FpsSelector(
    selected: FpsPreference,
    enabled: Boolean,
    onSelect: (FpsPreference) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FpsPreference.entries.forEach { f ->
            val active = f == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) Color(0xFF69C8FF).copy(alpha = 0.18f) else Color(0xFF1E262B))
                    .border(1.dp, if (active) Color(0xFF69C8FF) else Color(0xFF2C343A), RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { onSelect(f) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${f.value}fps",
                    color = if (active) Color(0xFF69C8FF) else if (enabled) Color(0xFFB7C3C9) else Color(0xFF4A5560),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ControlDock(state: MultiCamUiState, onRecordToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2024)),
        border = BorderStroke(1.dp, Color(0xFF303A41))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Filled.Mic, contentDescription = null, tint = Color(0xFF8EE7B5), modifier = Modifier.size(20.dp))
                    Text(text = "Audio su entrambi i file", color = Color(0xFFEAF1F4), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    enabled = state.hasPermissions && state.isCameraReady,
                    onClick = onRecordToggle,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isRecording) Color(0xFFE94141) else Color(0xFF35D07F),
                        contentColor = Color(0xFF07100B),
                        disabledContainerColor = Color(0xFF3A4146),
                        disabledContentColor = Color(0xFF9BA5AA)
                    ),
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (state.isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = if (state.isRecording) "Stop" else "Record",
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            AnimatedVisibility(visible = state.error != null) {
                Text(text = state.error.orEmpty(), color = Color(0xFFFFB3A9), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }

            AnimatedVisibility(visible = state.lastFiles.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.lastFiles.forEach { path ->
                        Text(text = path, color = Color(0xFFB7C3C9), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text = text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private val RequiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
