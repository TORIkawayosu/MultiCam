package com.example.multicamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * 背面レンズを任意の組み合わせで同時にプレビュー／録画する。各レンズは別々のMP4になる。
 *
 * - 同じ論理カメラ配下のレンズ（例: 広角＋望遠）は、1つのカメラを物理ストリーム指定で使う。
 * - 論理カメラに含まれないレンズ（例: 別IDの超広角）は、別のカメラとして追加で開く。
 *   この「複数カメラの同時オープン」を許すかは端末次第で、拒否された場合はエラーを返す。
 *
 * 録画は MediaStore（本体 / SDカード）の Movies/MultiCam へ直接書き込む。録画後のコピーや再変換はしない。
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
    private val pendingOpens = HashMap<String, MutableList<(CameraDevice?, String?) -> Unit>>()

    private var selected: List<LensInfo> = emptyList()
    private val textures = LinkedHashMap<String, TextureView>()

    @Volatile private var activeRecording: ActiveRecording? = null
    private var previewGeneration = 0
    private var sessionToken = 0
    private var previewRetries = 0
    private var displayListener: DisplayManager.DisplayListener? = null
    private val previewSizeById = HashMap<String, Size>()

    // ── プレビュー ─────────────────────────────────────────────────────────

    /**
     * 選択中のレンズだけでプレビューを（再）構築する。録画中は無視。
     * プレビューの縦横比は、録画される映像（選んだ画質のサイズ）に合わせる。
     */
    fun startPreview(selected: List<LensInfo>, containers: Map<String, FrameLayout>, settings: VideoSettings) {
        runOnMain {
            if (activeRecording != null) return@runOnMain
            previewSizeById.clear()
            selected.forEach { lens ->
                val recording = buildProfile(lens, settings.qualityFor(lens.physicalId), settings.fps)?.size
                previewSizeById[lens.physicalId] = choosePreviewSize(lens, recording)
            }
            val gate = Gate(++previewGeneration)
            this.selected = selected
            textures.clear()
            registerDisplayListener()

            selected.forEach { lens ->
                val container = containers[lens.physicalId] ?: return@forEach
                container.removeAllViews()
                val view = TextureView(context)
                view.surfaceTextureListener = PreviewListener(gate, lens)
                container.addView(view, FrameLayout.LayoutParams(-1, -1))
                textures[lens.physicalId] = view
            }
            checkReady(gate)
        }
    }

    private class Gate(val generation: Int) {
        var fired = false
    }

    private fun checkReady(gate: Gate) {
        if (gate.fired || gate.generation != previewGeneration) return
        if (textures.isNotEmpty() && textures.values.all { it.isAvailable }) {
            gate.fired = true
            previewRetries = 0
            runPreviewSession()
        }
    }

    private inner class PreviewListener(private val gate: Gate, private val lens: LensInfo) :
        TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            textures[lens.physicalId]?.let { applyTransform(lens, it, width, height) }
            checkReady(gate)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            textures[lens.physicalId]?.let { applyTransform(lens, it, width, height) }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    // ── 画面の向きに合わせたプレビューの回転 ────────────────────────────────

    @Suppress("DEPRECATION")
    private fun displayDegrees(): Int {
        val wm = context.getSystemService(WindowManager::class.java)
        return when (wm.defaultDisplay.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** 背面カメラの映像を、現在の画面の向きで正立させるのに必要な回転（時計回り・度）。 */
    private fun relativeRotation(lens: LensInfo): Int =
        (lens.sensorOrientation - displayDegrees() + 360) % 360

    /**
     * プレビューの補正。
     *
     * Camera2 は、プレビュー用の面（SurfaceTexture）に対して、映像を「端末の自然な向き（縦持ち）」で
     * 正立するように自動で回す。そのため、センサーの向きによる回転は、アプリでは不要で、
     * 「画面が自然な向きから、どれだけ回っているか」だけを打ち消せばよい。
     * ただし TextureView は、その映像を、ビューいっぱいに引き伸ばして表示する。
     * そこで、(1) 引き伸ばしを戻し、(2) 画面の回転を打ち消し、(3) 縦横比を保って枠に収める。
     * （録画用の面は自動回転されないので、録画は別に、センサーの向きで補正する。）
     */
    private fun applyTransform(lens: LensInfo, view: TextureView, viewW: Int = view.width, viewH: Int = view.height) {
        if (viewW <= 0 || viewH <= 0) return
        val vw = viewW.toFloat()
        val vh = viewH.toFloat()

        // 自然な向きで正立した映像の縦横（センサーが90/270度なら、バッファの縦横を入れ替える）
        val bw = previewSizeOf(lens).width.toFloat()
        val bh = previewSizeOf(lens).height.toFloat()
        val naturalSideways = lens.sensorOrientation % 180 != 0
        val cw = if (naturalSideways) bh else bw
        val ch = if (naturalSideways) bw else bh

        val rotation = (360 - displayDegrees()) % 360       // 画面の回転を打ち消す（時計回り・度）
        val swap = rotation % 180 != 0
        val rotatedW = if (swap) ch else cw
        val rotatedH = if (swap) cw else ch
        val scale = min(vw / rotatedW, vh / rotatedH)

        val matrix = Matrix().apply {
            postScale(cw / vw, ch / vh)                  // ビュー座標 → 映像の座標（引き伸ばしを戻す）
            postTranslate(-cw / 2f, -ch / 2f)
            postRotate(rotation.toFloat())
            postScale(scale, scale)
            postTranslate(vw / 2f, vh / 2f)
        }
        view.setTransform(matrix)
    }

    private fun applyAllTransforms() {
        selected.forEach { lens -> textures[lens.physicalId]?.let { applyTransform(lens, it) } }
    }

    /** 画面が回転したとき（横→逆向きの横など、サイズが変わらない場合も含む）に、プレビューの回転を更新する。 */
    private fun registerDisplayListener() {
        if (displayListener != null) return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) = applyAllTransforms()
        }
        displayListener = listener
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(listener, mainHandler)
    }

    private fun previewSizeOf(lens: LensInfo): Size = previewSizeById[lens.physicalId] ?: lens.previewSize

    /** 録画サイズと同じ縦横比で、1280x960 以内でいちばん大きいプレビューサイズを選ぶ。 */
    private fun choosePreviewSize(lens: LensInfo, recording: Size?): Size {
        if (recording == null) return lens.previewSize
        val target = recording.width.toFloat() / recording.height
        fun area(s: Size) = s.width.toLong() * s.height.toLong()
        fun diff(s: Size) = kotlin.math.abs(s.width.toFloat() / s.height - target)
        val sameAspect = lens.previewSizes.filter { diff(it) < 0.02f }
        val limit = 1280L * 960L
        return sameAspect.filter { area(it) <= limit }.maxByOrNull { area(it) }
            ?: sameAspect.minByOrNull { area(it) }
            ?: lens.previewSizes.filter { area(it) <= limit }.minByOrNull { diff(it) }
            ?: lens.previewSize
    }

    private fun neededCameraIds(): List<String> = selected.map { it.cameraId }.distinct()

    private fun runPreviewSession() {
        val lenses = selected
        if (lenses.isEmpty() || lenses.any { textures[it.physicalId]?.surfaceTexture == null }) return

        // もう使わないカメラは閉じる
        val needed = neededCameraIds()
        devices.keys.filter { it !in needed }.forEach { closeDevice(it) }

        val surfaces = HashMap<String, Surface>()
        lenses.forEach { lens ->
            val texture = textures.getValue(lens.physicalId).surfaceTexture!!
            texture.setDefaultBufferSize(previewSizeOf(lens).width, previewSizeOf(lens).height)
            surfaces[lens.physicalId] = Surface(texture)
        }
        val token = ++sessionToken
        bringUp(
            lenses = lenses,
            template = CameraDevice.TEMPLATE_PREVIEW,
            surfaceFor = { surfaces.getValue(it.physicalId) },
            fps = null,
            token = token,
            onAllConfigured = {
                previewRetries = 0
                callback.onReady(lenses.size)
            },
            onFailed = { message ->
                if (previewRetries < 1) {
                    // 起動直後の一時的な失敗に備えて、1回だけ自動で再試行する
                    previewRetries++
                    mainHandler.postDelayed({
                        if (activeRecording == null && token == sessionToken) runPreviewSession()
                    }, 500)
                } else {
                    previewRetries = 0
                    callback.onError(
                        message ?: context.getString(
                            if (needed.size > 1) R.string.error_multi_device_rejected else R.string.error_preview_rejected
                        ),
                        cameraDiagnostics()
                    )
                }
            }
        )
    }

    /**
     * レンズをカメラごとにまとめ、カメラを1台ずつ「開く → セッションを作る → 動かす」の順に立ち上げる。
     * （2台目を開く前に、1台目を動かしておく。端末によっては、同時の立ち上げに失敗するため。）
     * 全部できたら onAllConfigured、失敗したら onFailed（メッセージ、無ければ null）を呼ぶ。
     * token が古くなったら、その時点で処理を打ち切る。
     */
    private fun bringUp(
        lenses: List<LensInfo>,
        template: Int,
        surfaceFor: (LensInfo) -> Surface,
        fps: Int?,
        token: Int,
        onAllConfigured: () -> Unit,
        onFailed: (String?) -> Unit
    ) {
        val groups = lenses.groupBy { it.cameraId }.entries.toList()
        val multi = groups.size > 1

        fun step(i: Int) {
            if (token != sessionToken) return
            if (i >= groups.size) {
                onAllConfigured()
                return
            }
            val cameraId = groups[i].key
            val group = groups[i].value
            ensureOpen(cameraId, multi) { camera, error ->
                if (token != sessionToken) return@ensureOpen
                if (camera == null) {
                    onFailed(error)
                    return@ensureOpen
                }
                try {
                    val surfaces = group.map(surfaceFor)
                    val outputs = group.mapIndexed { index, lens ->
                        OutputConfiguration(surfaces[index]).apply {
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
                                    if (token != sessionToken) {
                                        session.close()
                                        return
                                    }
                                    try {
                                        session.setRepeatingRequest(request, null, mainHandler)
                                    } catch (t: Throwable) {
                                        onFailed(null)
                                        return
                                    }
                                    sessions[cameraId] = session
                                    step(i + 1)
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    if (token != sessionToken) return
                                    onFailed(null)
                                }
                            }
                        )
                    )
                } catch (t: Throwable) {
                    onFailed(null)
                }
            }
        }
        step(0)
    }

    /** カメラが開いていればそのまま、無ければ開く。同じカメラの二重オープンは避ける。 */
    @SuppressLint("MissingPermission")
    private fun ensureOpen(id: String, multi: Boolean, callbackFn: (CameraDevice?, String?) -> Unit) {
        devices[id]?.let {
            callbackFn(it, null)
            return
        }
        pendingOpens[id]?.let {
            it += callbackFn
            return
        }
        pendingOpens[id] = mutableListOf(callbackFn)

        fun finish(camera: CameraDevice?, error: String?) {
            pendingOpens.remove(id)?.forEach { it(camera, error) }
        }

        fun lost(message: String) {
            if (pendingOpens.containsKey(id)) {
                finish(null, message)
            } else {
                // 開いたあとに失われた（バックグラウンド化など）。録画中なら、ここまでを保存する。
                if (activeRecording != null) stopRecording()
                callback.onError(message, cameraDiagnostics())
            }
        }

        try {
            cameraManager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        devices[id] = camera
                        finish(camera, null)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        devices.remove(id)
                        sessions.remove(id)
                        lost(context.getString(R.string.error_camera_disconnected))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        devices.remove(id)
                        sessions.remove(id)
                        lost(
                            if (multi) context.getString(R.string.error_multi_device, "$id:$error")
                            else context.getString(R.string.error_camera_open, error)
                        )
                    }
                },
                mainHandler
            )
        } catch (t: Throwable) {
            finish(null, context.getString(R.string.error_camera_open, -1))
        }
    }

    private fun closeDevice(id: String) {
        sessions.remove(id)?.let { runCatching { it.close() } }
        devices.remove(id)?.let { runCatching { it.close() } }
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

        // 出力ファイルを SD / 本体の Movies/MultiCam に直接作り、そこへ録画する
        val outputs = ArrayList<VideoOutput>()
        val entries = ArrayList<RecordingEntry>()
        try {
            lenses.forEachIndexed { index, lens ->
                val output = MediaStoreVideoWriter.create(
                    context, target.mediaStoreVolume, "MultiCam_${timestamp}_${lens.fileTag}.mp4"
                )
                outputs += output
                val withAudio = when (settings.audio) {
                    AudioPreference.ALL -> true
                    AudioPreference.FIRST_ONLY -> index == 0
                    AudioPreference.OFF -> false
                }
                entries += RecordingEntry(
                    lens, createRecorder(output, profiles[index]!!, withAudio, relativeRotation(lens)), output
                )
            }
        } catch (t: Throwable) {
            discard(entries, outputs)
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

        bringUp(
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
                    discard(entries, outputs)
                    runPreviewSession()
                    val hint = if (settings.audio == AudioPreference.ALL && entries.size > 1) {
                        "\n" + context.getString(R.string.hint_audio)
                    } else ""
                    callback.onError(
                        context.getString(R.string.error_record_start, t.localizedMessage ?: "") + hint,
                        cameraDiagnostics()
                    )
                }
            },
            onFailed = { message ->
                discard(entries, outputs)
                runPreviewSession()
                callback.onError(
                    message ?: context.getString(
                        if (neededCameraIds().size > 1) R.string.error_multi_device_rejected else R.string.error_record_rejected
                    ),
                    cameraDiagnostics()
                )
            }
        )
    }

    /** 録画に失敗したとき、レコーダを解放し、作りかけの出力ファイルを削除する。 */
    private fun discard(entries: List<RecordingEntry>, outputs: List<VideoOutput>) {
        entries.forEach { releaseRecorderOnly(it.recorder) }
        outputs.forEach { MediaStoreVideoWriter.finish(context, it, keep = false) }
    }

    fun stopRecording() {
        val recording = activeRecording ?: return
        activeRecording = null
        sessions.values.forEach {
            runCatching { it.stopRepeating() }
            runCatching { it.abortCaptures() }
        }

        // 長い動画では停止に時間がかかるので、画面を止めないよう別スレッドで行う
        runOnWorker {
            val saved = ArrayList<String>()
            recording.entries.forEach { e ->
                val valid = stopRecorder(e.recorder)
                if (MediaStoreVideoWriter.finish(context, e.output, keep = valid)) saved += e.output.publicPath
            }
            runOnMain { runPreviewSession() }

            val total = recording.entries.size
            val message = when {
                saved.size == total -> context.getString(R.string.saved_to, recording.target.label)
                saved.isNotEmpty() -> context.getString(R.string.saved_partial)
                else -> context.getString(R.string.error_save_failed)
            }
            callback.onRecordingFinished(saved, message)
        }
    }

    fun release() {
        displayListener?.let { runCatching { context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) } }
        displayListener = null
        activeRecording?.let { rec ->
            activeRecording = null
            rec.entries.forEach { e ->
                val valid = stopRecorder(e.recorder)
                MediaStoreVideoWriter.finish(context, e.output, keep = valid)
            }
        }
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

    private fun createRecorder(
        output: VideoOutput, profile: VideoProfile, withAudio: Boolean, orientationHint: Int
    ): MediaRecorder {
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
            recorder.setOutputFile(output.pfd.fileDescriptor)
            recorder.setOrientationHint(orientationHint)   // 録画開始時の向きで、再生時に正立させる
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

    /** 停止して解放する。停止に成功（有効な動画が書けた）なら true。 */
    private fun stopRecorder(recorder: MediaRecorder): Boolean {
        val ok = runCatching { recorder.stop() }.isSuccess
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
        return ok
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
    val output: VideoOutput
)

private class ActiveRecording(val target: SaveTarget, val entries: List<RecordingEntry>)
