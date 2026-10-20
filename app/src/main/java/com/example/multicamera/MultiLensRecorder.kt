package com.example.multicamera

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
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer

/**
 * 背面レンズを任意の組み合わせで同時にプレビュー／録画する。各レンズは別々のMP4になる。
 *
 * - 同じ論理カメラ配下のレンズ（例: 広角＋望遠）は、1つのカメラを物理ストリーム指定で使う。
 * - 論理カメラに含まれないレンズ（例: 別IDの超広角）は、別のカメラとして追加で開く。
 *   この「複数カメラの同時オープン」を許すかは端末次第で、拒否された場合はエラーを返す。
 *
 * 音声は先頭のレンズのファイルに録音し、録画後に他のファイルへ複製（mux）する。
 * カメラ関連の状態は、すべてメインスレッドで扱う。
 */
class MultiLensRecorder(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onReady(lensCount: Int)
        fun onError(message: String, diagnostics: String)
        fun onRecordingStarted(summary: String)
        fun onRecordingFinished(files: List<String>, message: String)
    }

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private val devices = LinkedHashMap<String, CameraDevice>()
    private val sessions = LinkedHashMap<String, CameraCaptureSession>()

    private var plan: LensPlan? = null
    private var selected: List<LensInfo> = emptyList()
    private val textures = LinkedHashMap<String, TextureView>()

    @Volatile private var activeRecording: ActiveRecording? = null
    private var previewGeneration = 0
    private var sessionToken = 0
    private var opening = false
    private var reopenRequested = false

    // ── プレビュー ─────────────────────────────────────────────────────────

    /** 選択中のレンズだけでプレビューを（再）構築する。録画中は無視。 */
    fun startPreview(plan: LensPlan, selected: List<LensInfo>, containers: Map<String, FrameLayout>) {
        runOnMain {
            if (activeRecording != null) return@runOnMain
            val generation = ++previewGeneration
            this.plan = plan
            this.selected = selected
            textures.clear()

            val listener = PreviewReadyListener(generation)
            selected.forEach { lens ->
                val container = containers[lens.physicalId] ?: return@forEach
                container.removeAllViews()
                val view = TextureView(context)
                view.surfaceTextureListener = listener
                container.addView(view, FrameLayout.LayoutParams(-1, -1))
                textures[lens.physicalId] = view
            }
            listener.check()
        }
    }

    private inner class PreviewReadyListener(private val generation: Int) : TextureView.SurfaceTextureListener {
        private var fired = false

        fun check() {
            if (fired || generation != previewGeneration) return
            if (textures.isNotEmpty() && textures.values.all { it.isAvailable }) {
                fired = true
                openPreview()
            }
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = check()
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private fun neededCameraIds(): List<String> = selected.map { it.cameraId }.distinct()

    private fun openPreview() {
        if (opening) {              // 前回のオープン処理が終わるまで待つ
            reopenRequested = true
            return
        }
        val ids = neededCameraIds()

        // もう使わないカメラは閉じる
        devices.keys.filter { it !in ids }.forEach { closeDevice(it) }

        val missing = ids.filter { it !in devices }
        if (missing.isEmpty()) {
            runPreviewSession()
            return
        }
        opening = true
        openSequentially(missing, 0, multi = ids.size > 1) { ok ->
            opening = false
            if (reopenRequested) {
                reopenRequested = false
                openPreview()
            } else if (ok) {
                runPreviewSession()
            }
        }
    }

    /** 安全のため、カメラは1台ずつ順番に開く。 */
    @SuppressLint("MissingPermission")
    private fun openSequentially(ids: List<String>, index: Int, multi: Boolean, done: (Boolean) -> Unit) {
        if (index >= ids.size) {
            done(true)
            return
        }
        val id = ids[index]
        var finished = false
        try {
            cameraManager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        devices[id] = camera
                        if (!finished) {
                            finished = true
                            openSequentially(ids, index + 1, multi, done)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        devices.remove(id)
                        sessions.remove(id)
                        if (!finished) {
                            finished = true
                            fail(context.getString(R.string.error_camera_disconnected), done)
                        } else {
                            callback.onError(context.getString(R.string.error_camera_disconnected), cameraDiagnostics())
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        devices.remove(id)
                        sessions.remove(id)
                        val msg = if (multi) context.getString(R.string.error_multi_device, "$id:$error")
                        else context.getString(R.string.error_camera_open, error)
                        if (!finished) {
                            finished = true
                            fail(msg, done)
                        } else {
                            callback.onError(msg, cameraDiagnostics())
                        }
                    }
                },
                mainHandler
            )
        } catch (t: Throwable) {
            val msg = if (multi) context.getString(R.string.error_multi_device, "$id:${t.message}")
            else context.getString(R.string.error_camera_open, -1)
            fail(msg, done)
        }
    }

    private fun fail(message: String, done: (Boolean) -> Unit) {
        callback.onError(message, cameraDiagnostics())
        done(false)
    }

    private fun closeDevice(id: String) {
        sessions.remove(id)?.let { runCatching { it.close() } }
        devices.remove(id)?.let { runCatching { it.close() } }
    }

    private fun runPreviewSession() {
        val lenses = selected
        if (lenses.isEmpty() || lenses.any { textures[it.physicalId]?.surfaceTexture == null }) return

        val surfaces = HashMap<String, Surface>()
        lenses.forEach { lens ->
            val texture = textures.getValue(lens.physicalId).surfaceTexture!!
            texture.setDefaultBufferSize(lens.previewSize.width, lens.previewSize.height)
            surfaces[lens.physicalId] = Surface(texture)
        }
        val token = ++sessionToken
        configureSessions(
            lenses = lenses,
            template = CameraDevice.TEMPLATE_PREVIEW,
            surfaceFor = { surfaces.getValue(it.physicalId) },
            fps = null,
            token = token,
            onAllConfigured = { callback.onReady(lenses.size) },
            onFailed = {
                callback.onError(
                    context.getString(
                        if (neededCameraIds().size > 1) R.string.error_multi_device_rejected else R.string.error_preview_rejected
                    ),
                    cameraDiagnostics()
                )
            }
        )
    }

    /**
     * レンズをカメラごとにまとめ、カメラごとに1セッションを作る。
     * すべて構成できたら、全セッションの繰り返しリクエストを開始して onAllConfigured を呼ぶ。
     */
    private fun configureSessions(
        lenses: List<LensInfo>,
        template: Int,
        surfaceFor: (LensInfo) -> Surface,
        fps: Int?,
        token: Int,
        onAllConfigured: () -> Unit,
        onFailed: () -> Unit
    ) {
        val groups = lenses.groupBy { it.cameraId }
        var remaining = groups.size
        var failed = false
        val ready = ArrayList<Pair<CameraCaptureSession, CaptureRequest>>()

        groups.forEach { (cameraId, group) ->
            val camera = devices[cameraId]
            if (camera == null) {
                if (!failed) { failed = true; onFailed() }
                return
            }
            try {
                val surfaces = group.map(surfaceFor)
                val outputs = group.mapIndexed { i, lens ->
                    OutputConfiguration(surfaces[i]).apply {
                        if (lens.isPhysicalStream) setPhysicalCameraId(lens.physicalId)
                    }
                }
                val request = camera.createCaptureRequest(template).apply {
                    surfaces.forEach { addTarget(it) }
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    if (fps != null) chooseFpsRange(cameraId, fps)?.let {
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                    }
                }.build()

                camera.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        ContextCompat.getMainExecutor(context),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (token != sessionToken || failed) {
                                    session.close()
                                    return
                                }
                                sessions[cameraId] = session
                                ready += session to request
                                if (--remaining == 0) {
                                    try {
                                        ready.forEach { (s, r) -> s.setRepeatingRequest(r, null, mainHandler) }
                                    } catch (t: Throwable) {
                                        failed = true
                                        onFailed()
                                        return
                                    }
                                    onAllConfigured()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                if (token != sessionToken || failed) return
                                failed = true
                                onFailed()
                            }
                        }
                    )
                )
            } catch (t: Throwable) {
                if (!failed) { failed = true; onFailed() }
                return
            }
        }
    }

    // ── 録画 ───────────────────────────────────────────────────────────────

    fun startRecording(target: SaveTarget, timestamp: String, settings: VideoSettings) {
        if (activeRecording != null) return
        val lenses = selected
        if (lenses.isEmpty() || neededCameraIds().any { it !in devices }) {
            callback.onError(context.getString(R.string.error_not_ready), cameraDiagnostics())
            return
        }

        val profiles = lenses.map { buildProfile(it, settings.qualityFor(it.physicalId), settings.fps) }
        if (profiles.any { it == null }) {
            callback.onError(context.getString(R.string.error_no_profile), cameraDiagnostics())
            return
        }

        target.dir.mkdirs()
        val entries = ArrayList<RecordingEntry>()
        try {
            lenses.forEachIndexed { index, lens ->
                val finalFile = File(target.dir, "MultiCam_${timestamp}_${lens.fileTag}.mp4")
                // 先頭レンズは音声付きで直接最終ファイルへ。他は映像のみの一時ファイル。
                val recordFile = if (index == 0) finalFile
                else File(target.dir, "MultiCam_${timestamp}_${lens.fileTag}_video.mp4")
                val recorder = createRecorder(recordFile, profiles[index]!!, withAudio = index == 0)
                entries += RecordingEntry(lens, recorder, recordFile, finalFile)
            }
        } catch (t: Throwable) {
            entries.forEach { releaseRecorderOnly(it.recorder) }
            callback.onError(context.getString(R.string.error_recorder_prepare, t.localizedMessage ?: ""), cameraDiagnostics())
            return
        }

        // 録画中はプレビューを止め、エンコーダ用の面だけでセッションを作る
        sessions.values.forEach {
            runCatching { it.stopRepeating() }
            runCatching { it.abortCaptures() }
        }
        val token = ++sessionToken
        val surfaceById = entries.associate { it.lens.physicalId to it.recorder.surface }

        configureSessions(
            lenses = lenses,
            template = CameraDevice.TEMPLATE_RECORD,
            surfaceFor = { surfaceById.getValue(it.physicalId) },
            fps = settings.fps.value,
            token = token,
            onAllConfigured = {
                try {
                    entries.forEach { it.recorder.start() }
                    activeRecording = ActiveRecording(target, entries)
                    val summary = entries.indices.joinToString(" / ") { i ->
                        "${entries[i].lens.label} ${profiles[i]!!.label}"
                    } + " @${settings.fps.value}fps"
                    callback.onRecordingStarted(summary)
                } catch (t: Throwable) {
                    entries.forEach { releaseRecorderOnly(it.recorder) }
                    entries.forEach { it.recordFile.delete() }
                    runPreviewSession()
                    callback.onError(
                        context.getString(R.string.error_record_start, t.localizedMessage ?: ""),
                        cameraDiagnostics()
                    )
                }
            },
            onFailed = {
                entries.forEach { releaseRecorderOnly(it.recorder) }
                entries.forEach { it.recordFile.delete() }
                runPreviewSession()
                callback.onError(
                    context.getString(
                        if (neededCameraIds().size > 1) R.string.error_multi_device_rejected else R.string.error_record_rejected
                    ),
                    cameraDiagnostics()
                )
            }
        )
    }

    fun stopRecording() {
        val recording = activeRecording ?: return
        sessions.values.forEach {
            runCatching { it.stopRepeating() }
            runCatching { it.abortCaptures() }
        }
        recording.entries.forEach { stopRecorder(it.recorder) }

        runOnWorker {
            val audioFile = recording.entries.first().finalFile
            val toExport = ArrayList<File>()
            val muxErrors = ArrayList<String>()

            recording.entries.forEachIndexed { index, e ->
                if (index == 0) {
                    toExport += e.finalFile
                    return@forEachIndexed
                }
                try {
                    muxAudioIntoVideo(e.recordFile, audioFile, e.finalFile)
                    e.recordFile.delete()
                    toExport += e.finalFile
                } catch (t: Throwable) {
                    muxErrors += "${e.lens.label}: ${t.localizedMessage}"
                    e.finalFile.delete()
                    toExport += e.recordFile          // 音声なしで保存
                }
            }

            activeRecording = null
            runOnMain { runPreviewSession() }

            val exported = MediaStoreVideoExporter.export(context, toExport, recording.target.mediaStoreVolume)
            exported.forEach { (source, _) -> source.delete() }   // 公開できたものは重複を残さない

            val exportedSources = exported.map { it.first }.toSet()
            val paths = exported.map { it.second } +
                toExport.filter { it !in exportedSources }.map { it.absolutePath }
            val message = when {
                exported.size == toExport.size && muxErrors.isEmpty() ->
                    context.getString(R.string.saved_to, recording.target.label)
                exported.size == toExport.size ->
                    context.getString(R.string.saved_audio_mux_failed, muxErrors.joinToString())
                exported.isNotEmpty() -> context.getString(R.string.saved_partial)
                else -> context.getString(R.string.saved_app_only)
            }
            callback.onRecordingFinished(paths, message)
        }
    }

    fun release() {
        runCatching { activeRecording?.entries?.forEach { stopRecorder(it.recorder) } }
        activeRecording = null
        sessions.values.forEach { runCatching { it.close() } }
        devices.values.forEach { runCatching { it.close() } }
        sessions.clear()
        devices.clear()
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }

    // ── プロファイル・レコーダ ──────────────────────────────────────────────

    private fun buildProfile(lens: LensInfo, quality: QualityPreference, fps: FpsPreference): VideoProfile? {
        val sizes = lens.videoSizes.ifEmpty { return null }
        val size = when (quality) {
            QualityPreference.MAX -> sizes.first()
            QualityPreference.UHD -> sizes.firstOrNull { it.width >= 3840 }
                ?: sizes.firstOrNull { it.width >= 2560 }
                ?: sizes.first()
            QualityPreference.FHD -> sizes.firstOrNull { it.width in 1920..2559 }
                ?: sizes.firstOrNull { it.width in 1280..1919 }
                ?: sizes.last()
            QualityPreference.HD -> sizes.firstOrNull { it.width in 1280..1919 }
                ?: sizes.firstOrNull { it.width in 640..1279 }
                ?: sizes.last()
        }
        return VideoProfile(size, fps.value, bitRateFor(size), "${size.width}x${size.height}")
    }

    /** 端末が対応する AE の fps 範囲から、指定 fps にいちばん近いものを選ぶ。 */
    private fun chooseFpsRange(cameraId: String, fps: Int): Range<Int>? {
        val ranges = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
    }

    private fun createRecorder(file: File, profile: VideoProfile, withAudio: Boolean): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            if (withAudio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
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
        } catch (t: Throwable) {
            releaseRecorderOnly(recorder)
            throw t
        }
        return recorder
    }

    private fun stopRecorder(recorder: MediaRecorder) {
        runCatching { recorder.stop() }
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
    }

    private fun releaseRecorderOnly(recorder: MediaRecorder) {
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
    }

    private fun bitRateFor(size: Size): Int {
        val pixels = size.width.toLong() * size.height.toLong()
        return when {
            pixels >= 3840L * 2160L -> 80_000_000
            pixels >= 2560L * 1440L -> 48_000_000
            pixels >= 1920L * 1080L -> 28_000_000
            pixels >= 1280L * 720L -> 14_000_000
            else -> 8_000_000
        }
    }

    // ── 音声の複製（mux） ───────────────────────────────────────────────────

    private fun muxAudioIntoVideo(videoOnlyFile: File, audioSourceFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            videoExtractor.setDataSource(videoOnlyFile.absolutePath)
            audioExtractor.setDataSource(audioSourceFile.absolutePath)

            val tracks = mutableListOf<Pair<MediaExtractor, Int>>()
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) {
                    tracks += videoExtractor to muxer.addTrack(format)
                    videoExtractor.selectTrack(i)
                }
            }
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    tracks += audioExtractor to muxer.addTrack(format)
                    audioExtractor.selectTrack(i)
                }
            }
            muxer.start()
            started = true
            tracks.forEach { (extractor, track) -> copySelectedTrack(extractor, muxer, track) }
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun copySelectedTrack(extractor: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    // ── 診断・ヘルパー ──────────────────────────────────────────────────────

    private fun cameraDiagnostics(): String = buildString {
        cameraManager.cameraIdList.forEach { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }
            appendLine("Camera $id facing=$facing physical=${chars.physicalCameraIds}")
        }
    }

    private fun runOnWorker(block: () -> Unit) {
        if (workerThread == null) {
            workerThread = HandlerThread("MultiLensWorker").also {
                it.start()
                workerHandler = Handler(it.looper)
            }
        }
        workerHandler?.post(block)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}

private data class VideoProfile(val size: Size, val fps: Int, val bitRate: Int, val label: String)

private class RecordingEntry(
    val lens: LensInfo,
    val recorder: MediaRecorder,
    val recordFile: File,     // 実際に録画するファイル
    val finalFile: File       // 最終的なファイル（先頭レンズは recordFile と同じ）
)

private class ActiveRecording(val target: SaveTarget, val entries: List<RecordingEntry>)
