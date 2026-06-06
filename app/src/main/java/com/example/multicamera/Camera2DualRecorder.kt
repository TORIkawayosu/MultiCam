package com.example.multicamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.atan

class Camera2DualRecorder(
    private val context: Context,
    private val ultraContainer: FrameLayout,
    private val wideContainer: FrameLayout,
    private val callback: Callback
) {
    interface Callback {
        fun onCamera2Ready(status: String, ultraDetails: String, wideDetails: String)
        fun onCamera2Error(message: String, diagnostics: String)
        fun onCamera2RecordingStarted(status: String)
        fun onCamera2RecordingFinished(files: List<String>, message: String)
    }

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var activePlan: Camera2Plan? = null
    private var activeRecording: Camera2Recording? = null
    private var ultraTexture: TextureView? = null
    private var wideTexture: TextureView? = null

    // Freeze the last frame shown during recording
    private var frozenUltraSurface: Surface? = null
    private var frozenWideSurface: Surface? = null

    fun startPreview() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback.onCamera2Error(
                "Fallback Camera2 richiede Android 9+ per indirizzare i sensori fisici.",
                cameraDiagnostics()
            )
            return
        }

        runOnMain {
            ultraContainer.removeAllViews()
            wideContainer.removeAllViews()
            val ultra = TextureView(context)
            val wide = TextureView(context)
            ultraContainer.addView(ultra, FrameLayout.LayoutParams(-1, -1))
            wideContainer.addView(wide, FrameLayout.LayoutParams(-1, -1))
            ultraTexture = ultra
            wideTexture = wide

            val listener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    if (ultraTexture?.isAvailable == true && wideTexture?.isAvailable == true) {
                        openBestPreview()
                    }
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
            ultra.surfaceTextureListener = listener
            wide.surfaceTextureListener = listener

            if (ultra.isAvailable && wide.isAvailable) {
                openBestPreview()
            }
        }
    }

    fun startRecording(outputDir: File, timestamp: String, settings: DualVideoSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val plan = activePlan ?: run {
            callback.onCamera2Error("Camera2 non e pronto.", cameraDiagnostics())
            return
        }
        if (activeRecording != null) return

        outputDir.mkdirs()
        val ultraFile = File(outputDir, "MultiCam_${timestamp}_0_5x.mp4")
        val wideTempFile = File(outputDir, "MultiCam_${timestamp}_1x_video.mp4")
        val wideFile = File(outputDir, "MultiCam_${timestamp}_1x.mp4")

        // Build profiles from user settings
        val ultraProfile = buildProfileFromSettings(plan, settings.ultraQuality, settings.ultraFps)
        val wideProfile = buildProfileFromSettings(plan, settings.wideQuality, settings.wideFps)

        if (ultraProfile == null || wideProfile == null) {
            callback.onCamera2Error("Nessun profilo video valido per i settings scelti.", cameraDiagnostics())
            return
        }

        startRecordingNoPreview(plan, ultraProfile, wideProfile, ultraFile, wideTempFile, wideFile)
    }

    fun stopRecording() {
        val recording = activeRecording ?: return
        captureSession?.stopRepeating()
        captureSession?.abortCaptures()
        recording.stopRecorders()

        runOnWorker {
            try {
                muxAudioIntoVideo(
                    videoOnlyFile = recording.wideVideoOnlyFile,
                    audioSourceFile = recording.ultraFile,
                    outputFile = recording.wideFinalFile
                )
                recording.wideVideoOnlyFile.delete()
                activeRecording = null
                runPreviewSession(recording.plan)
                val exportedFiles = MediaStoreVideoExporter.export(
                    context,
                    listOf(recording.ultraFile, recording.wideFinalFile)
                )
                callback.onCamera2RecordingFinished(
                    exportedFiles.ifEmpty {
                        listOf(recording.ultraFile, recording.wideFinalFile).map { it.absolutePath }
                    },
                    if (exportedFiles.size == 2) {
                        "Video salvati in Movies/MultiCam"
                    } else {
                        "Video salvati nello spazio app; export pubblico incompleto"
                    }
                )
            } catch (error: Throwable) {
                activeRecording = null
                runPreviewSession(recording.plan)
                val files = listOf(recording.ultraFile, recording.wideVideoOnlyFile)
                val exportedFiles = MediaStoreVideoExporter.export(context, files)
                callback.onCamera2RecordingFinished(
                    exportedFiles.ifEmpty { files.map { it.absolutePath } },
                    if (exportedFiles.isNotEmpty()) {
                        "Salvati, audio 1x non duplicato: ${error.localizedMessage}"
                    } else {
                        "Salvati, audio 1x non duplicato: ${error.localizedMessage}"
                    }
                )
            }
        }
    }

    fun release() {
        runCatching { activeRecording?.stopRecorders() }
        activeRecording = null
        captureSession?.close()
        cameraDevice?.close()
        captureSession = null
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    // ── Preview session (2 streams: preview only) ──────────────────────────

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openBestPreview() {
        val plan = buildBestPlan() ?: run {
            callback.onCamera2Error(
                "Camera2 non trova una camera logica posteriore con due sensori fisici video.",
                cameraDiagnostics()
            )
            return
        }

        ensureThread()
        captureSession?.close()
        cameraDevice?.close()
        activePlan = plan

        cameraManager.openCamera(
            plan.logicalCameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    runPreviewSession(plan)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    callback.onCamera2Error("Camera2 disconnessa.", cameraDiagnostics())
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    callback.onCamera2Error("Camera2 errore apertura: $error", cameraDiagnostics())
                }
            },
            cameraHandler
        )
    }

    private fun runPreviewSession(plan: Camera2Plan) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val camera = cameraDevice ?: return
        val ultraST = ultraTexture?.surfaceTexture ?: return
        val wideST = wideTexture?.surfaceTexture ?: return
        val previewSize = plan.previewSize

        ultraST.setDefaultBufferSize(previewSize.width, previewSize.height)
        wideST.setDefaultBufferSize(previewSize.width, previewSize.height)

        val ultraSurface = Surface(ultraST)
        val wideSurface = Surface(wideST)
        frozenUltraSurface = ultraSurface
        frozenWideSurface = wideSurface

        val outputs = listOf(
            OutputConfiguration(ultraSurface).apply { setPhysicalCameraId(plan.ultraPhysicalId) },
            OutputConfiguration(wideSurface).apply { setPhysicalCameraId(plan.widePhysicalId) }
        )

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(ultraSurface)
            addTarget(wideSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                ContextCompat.getMainExecutor(context),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(request.build(), null, cameraHandler)
                        callback.onCamera2Ready(
                            "Fallback Camera2 pronto",
                            plan.ultraDetails,
                            plan.wideDetails
                        )
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        callback.onCamera2Error(
                            "La sessione preview Camera2 e stata rifiutata.",
                            cameraDiagnostics()
                        )
                    }
                }
            )
        )
    }

    // ── Recording session (2 streams: recorders only, NO preview) ──────────

    private fun startRecordingNoPreview(
        plan: Camera2Plan,
        ultraProfile: VideoProfile,
        wideProfile: VideoProfile,
        ultraFile: File,
        wideTempFile: File,
        wideFile: File
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val camera = cameraDevice ?: run {
            callback.onCamera2Error("Camera2 device non aperto", cameraDiagnostics())
            return
        }

        // Stop preview first
        captureSession?.stopRepeating()
        captureSession?.abortCaptures()

        val ultraRecorder = createRecorder(ultraFile, ultraProfile, withAudio = true)
        val wideRecorder = createRecorder(wideTempFile, wideProfile, withAudio = false)
        val ultraRecordSurface = ultraRecorder.surface
        val wideRecordSurface = wideRecorder.surface

        // Only recorder surfaces — NO preview surfaces
        val outputs = listOf(
            OutputConfiguration(ultraRecordSurface).apply { setPhysicalCameraId(plan.ultraPhysicalId) },
            OutputConfiguration(wideRecordSurface).apply { setPhysicalCameraId(plan.widePhysicalId) }
        )

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(ultraRecordSurface)
            addTarget(wideRecordSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                android.util.Range(ultraProfile.fps, ultraProfile.fps))
        }

        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                ContextCompat.getMainExecutor(context),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(request.build(), null, cameraHandler)
                        ultraRecorder.start()
                        wideRecorder.start()
                        activeRecording = Camera2Recording(
                            plan = plan,
                            ultraRecorder = ultraRecorder,
                            wideRecorder = wideRecorder,
                            ultraFile = ultraFile,
                            wideVideoOnlyFile = wideTempFile,
                            wideFinalFile = wideFile
                        )
                        val label = "${ultraProfile.label}@${ultraProfile.fps}fps / ${wideProfile.label}@${wideProfile.fps}fps"
                        callback.onCamera2RecordingStarted("REC $label")
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        releaseRecordersOnly(ultraRecorder, wideRecorder)
                        callback.onCamera2Error(
                            "Sessione registrazione rifiutata dal device.",
                            cameraDiagnostics()
                        )
                    }
                }
            )
        )
    }

    // ── Profile resolution ─────────────────────────────────────────────────

    private fun buildProfileFromSettings(
        plan: Camera2Plan,
        quality: QualityPreference,
        fps: FpsPreference
    ): VideoProfile? {
        val allSizes = plan.allSizes.ifEmpty { return null }
        val targetSize = when (quality) {
            QualityPreference.MAX -> allSizes.first()
            QualityPreference.UHD -> allSizes.firstOrNull { it.width >= 3840 }
                ?: allSizes.firstOrNull { it.width >= 2560 }
                ?: allSizes.first()
            QualityPreference.FHD -> allSizes.firstOrNull { it.width in 1920..2559 }
                ?: allSizes.firstOrNull { it.width < 1920 && it.width >= 1280 }
                ?: allSizes.last()
            QualityPreference.HD -> allSizes.firstOrNull { it.width in 1280..1919 }
                ?: allSizes.firstOrNull { it.width < 1280 && it.width >= 640 }
                ?: allSizes.last()
        }
        return VideoProfile(
            size = targetSize,
            fps = fps.value,
            bitRate = bitRateFor(targetSize),
            label = "${targetSize.width}x${targetSize.height}"
        )
    }

    // ── Recorder creation ──────────────────────────────────────────────────

    private fun createRecorder(file: File, profile: VideoProfile, withAudio: Boolean): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        if (withAudio) {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(file.absolutePath)
        recorder.setVideoEncodingBitRate(profile.bitRate)
        recorder.setVideoFrameRate(profile.fps)
        recorder.setVideoSize(profile.size.width, profile.size.height)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (withAudio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(192_000)
            recorder.setAudioSamplingRate(48_000)
        }
        recorder.prepare()
        return recorder
    }

    // ── Plan building ──────────────────────────────────────────────────────

    private fun buildBestPlan(): Camera2Plan? {
        val logicalRear = cameraManager.cameraIdList
            .mapNotNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (
                    facing == CameraCharacteristics.LENS_FACING_BACK &&
                    capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) &&
                    chars.physicalCameraIds.size >= 2
                ) {
                    id to chars
                } else null
            }
            .maxByOrNull { (_, chars) -> chars.physicalCameraIds.size }
            ?: return null

        val logicalId = logicalRear.first
        val physicals = logicalRear.second.physicalCameraIds
            .map { id -> physicalDescriptor(id) }
            .filter { it.videoSizes.isNotEmpty() }
            .sortedByDescending { it.horizontalFovDegrees }

        if (physicals.size < 2) return null

        val ultra = physicals.first()
        val wide = physicals.drop(1).maxByOrNull { it.horizontalFovDegrees } ?: return null

        // All sizes from the wider sensor (intersection too narrow); use ultra sizes as base
        val allSizes = ultra.videoSizes
            .sortedWith(compareByDescending<Size> { it.width.toLong() * it.height.toLong() }.thenByDescending { it.width })

        val previewSize = allSizes.firstOrNull { it.width <= 1920 && it.height <= 1080 }
            ?: allSizes.lastOrNull()
            ?: return null

        return Camera2Plan(
            logicalCameraId = logicalId,
            ultraPhysicalId = ultra.id,
            widePhysicalId = wide.id,
            previewSize = previewSize,
            allSizes = allSizes,
            ultraDetails = ultra.detailLabel("physical"),
            wideDetails = wide.detailLabel("physical")
        )
    }

    private fun physicalDescriptor(id: String): PhysicalCameraDescriptor {
        val chars = cameraManager.getCameraCharacteristics(id)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSizes = streamMap?.getOutputSizes(MediaRecorder::class.java)
            ?.filter { it.width >= 640 && it.height >= 480 }
            ?.distinct()
            ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height.toLong() }.thenByDescending { it.width })
            .orEmpty()
        val minFocal = focalLengths?.minOrNull()
        return PhysicalCameraDescriptor(
            id = id,
            focalLengthMm = minFocal,
            horizontalFovDegrees = horizontalFov(sensorSize, minFocal),
            videoSizes = videoSizes
        )
    }

    // ── Diagnostics ────────────────────────────────────────────────────────

    private fun cameraDiagnostics(): String {
        return buildString {
            cameraManager.cameraIdList.forEach { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.joinToString(prefix = "[", postfix = "]").orEmpty()
                appendLine("Camera $id facing=$facing physical=${chars.physicalCameraIds} caps=$caps")
                val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(MediaRecorder::class.java)
                    ?.take(8)
                    ?.joinToString { "${it.width}x${it.height}" }.orEmpty()
                appendLine("  MediaRecorder sizes: $sizes")
            }
        }
    }

    // ── Audio muxing ───────────────────────────────────────────────────────

    private fun muxAudioIntoVideo(videoOnlyFile: File, audioSourceFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            videoExtractor.setDataSource(videoOnlyFile.absolutePath)
            audioExtractor.setDataSource(audioSourceFile.absolutePath)

            val trackMap = mutableListOf<Pair<MediaExtractor, Int>>()
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    val muxTrack = muxer.addTrack(format)
                    videoExtractor.selectTrack(i)
                    trackMap.add(videoExtractor to muxTrack)
                }
            }
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    val muxTrack = muxer.addTrack(format)
                    audioExtractor.selectTrack(i)
                    trackMap.add(audioExtractor to muxTrack)
                }
            }
            muxer.start()
            trackMap.forEach { (extractor, muxTrack) -> copySelectedTrack(extractor, muxer, muxTrack) }
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun copySelectedTrack(extractor: MediaExtractor, muxer: MediaMuxer, muxTrack: Int) {
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            info.offset = 0
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(muxTrack, buffer, info)
            extractor.advance()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun ensureThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("Camera2DualRecorder").also {
            it.start()
            cameraHandler = Handler(it.looper)
        }
    }

    private fun runOnWorker(block: () -> Unit) {
        ensureThread()
        cameraHandler?.post(block)
    }

    private fun runOnMain(block: () -> Unit) {
        ContextCompat.getMainExecutor(context).execute(block)
    }

    private fun releaseRecordersOnly(vararg recorders: MediaRecorder) {
        recorders.forEach { recorder ->
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }
    }

    private fun Camera2Recording.stopRecorders() {
        listOf(ultraRecorder, wideRecorder).forEach { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }
    }

    private fun bitRateFor(size: Size): Int {
        val pixels = size.width.toLong() * size.height.toLong()
        return when {
            pixels >= 3840L * 2160L -> 80_000_000
            pixels >= 2560L * 1440L -> 48_000_000
            pixels >= 1920L * 1080L -> 28_000_000
            pixels >= 1280L * 720L  -> 14_000_000
            else                    ->  8_000_000
        }
    }

    private fun horizontalFov(sensorSize: android.util.SizeF?, focalLengthMm: Float?): Float {
        if (sensorSize == null || focalLengthMm == null || focalLengthMm <= 0f) return 0f
        return Math.toDegrees(2.0 * atan(sensorSize.width.toDouble() / (2.0 * focalLengthMm))).toFloat()
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

private data class Camera2Plan(
    val logicalCameraId: String,
    val ultraPhysicalId: String,
    val widePhysicalId: String,
    val previewSize: Size,
    val allSizes: List<Size>,           // sorted best→worst, used to resolve quality prefs
    val ultraDetails: String,
    val wideDetails: String
)

private data class VideoProfile(
    val size: Size,
    val fps: Int,
    val bitRate: Int,
    val label: String
)

private data class Camera2Recording(
    val plan: Camera2Plan,
    val ultraRecorder: MediaRecorder,
    val wideRecorder: MediaRecorder,
    val ultraFile: File,
    val wideVideoOnlyFile: File,
    val wideFinalFile: File
)

private data class PhysicalCameraDescriptor(
    val id: String,
    val focalLengthMm: Float?,
    val horizontalFovDegrees: Float,
    val videoSizes: List<Size>
) {
    fun detailLabel(prefix: String): String {
        val focal = focalLengthMm?.let { "%.1f mm".format(Locale.US, it) } ?: "focale n/d"
        val fov = if (horizontalFovDegrees > 0f) {
            "%.0f deg".format(Locale.US, horizontalFovDegrees)
        } else {
            "FOV n/d"
        }
        val max = videoSizes.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "video n/d"
        return "$prefix ID $id - $focal - $fov - max $max"
    }
}
