package com.example.multicamera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.util.Size
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 背面の論理マルチカメラから物理レンズを列挙し、
 * 35mm換算焦点距離から「超広角 / 広角 / 望遠」に分類する。
 */
object LensDetector {

    private class Raw(
        val id: String,
        val focalMm: Float,
        val equivMm: Float,
        val fovDeg: Float,
        val pixelArea: Long,
        val videoSizes: List<Size>,
        val previewSize: Size,
        val cameraId: String,
        val isPhysicalStream: Boolean
    )

    fun detect(context: Context): LensPlan? {
        val manager = context.getSystemService(CameraManager::class.java)

        val logical = manager.cameraIdList.mapNotNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            val back = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            val multi = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            if (back && multi && chars.physicalCameraIds.size >= 2) id to chars else null
        }.maxByOrNull { it.second.physicalCameraIds.size } ?: return null

        val physicalIds = logical.second.physicalCameraIds
        val physical = physicalIds.mapNotNull { describe(manager, it, logical.first, true, allowFallback = true) }
        if (physical.isEmpty()) return null

        // 論理カメラに含まれない背面カメラ（例: 超広角が別IDで公開されている端末）も、単独のカメラとして追加する。
        val standalone = manager.cameraIdList.filter { id ->
            if (id == logical.first || id in physicalIds) return@filter false
            val c = manager.getCameraCharacteristics(id)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                !caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        }.mapNotNull { describe(manager, it, it, false, allowFallback = false) }
        val raws = physical + standalone
        if (raws.size < 2) return null

        // メイン（広角）レンズ：換算20〜40mmのうち画素数が最大のもの。なければ26mmに最も近いもの。
        val main = raws.filter { it.equivMm in 20f..40f }.maxByOrNull { it.pixelArea }
            ?: raws.minByOrNull { abs(it.equivMm - 26f) }!!

        val used = HashSet<String>()
        val lenses = raws.sortedBy { it.equivMm }.map { raw ->
            val zoom = raw.equivMm / main.equivMm
            val type = when {
                zoom < 0.75f -> LensType.ULTRA_WIDE
                zoom > 1.5f -> LensType.TELE
                else -> LensType.WIDE
            }
            val typeName = context.getString(
                when (type) {
                    LensType.ULTRA_WIDE -> R.string.lens_ultrawide
                    LensType.WIDE -> R.string.lens_wide
                    LensType.TELE -> R.string.lens_tele
                }
            )
            val zoomText = zoomText(zoom)
            var tag = "${type.tag}_${zoomText.replace('.', '_')}"
            if (!used.add(tag)) {            // 同じ種類・倍率が複数あるときはIDで区別
                tag += "_id${raw.id}"
                used.add(tag)
            }
            LensInfo(
                physicalId = raw.id,
                cameraId = raw.cameraId,
                isPhysicalStream = raw.isPhysicalStream,
                type = type,
                zoomRatio = zoom,
                label = "$typeName $zoomText",
                detail = context.getString(
                    R.string.lens_detail, raw.id, raw.focalMm, raw.equivMm.roundToInt(), raw.fovDeg
                ),
                fileTag = tag,
                videoSizes = raw.videoSizes,
                previewSize = raw.previewSize
            )
        }
        return LensPlan(logical.first, lenses)
    }

    private fun describe(
        manager: CameraManager, id: String, cameraId: String, isPhysicalStream: Boolean, allowFallback: Boolean
    ): Raw? {
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null

        val byArea = compareByDescending<Size> { it.width.toLong() * it.height.toLong() }
            .thenByDescending { it.width }
        val recorderSizes = map.getOutputSizes(MediaRecorder::class.java)
            ?.filter { it.width >= 640 && it.height >= 480 }
            ?.distinct()?.sortedWith(byArea).orEmpty()
        // MediaRecorder用のサイズが取れない端末でも、レンズ自体は隠さない（通常の出力サイズで代用）
        val videoSizes = if (recorderSizes.isNotEmpty() || !allowFallback) recorderSizes else run {
            map.getOutputSizes(SurfaceTexture::class.java)
                ?.filter { it.width >= 640 && it.height >= 480 }
                ?.distinct()?.sortedWith(byArea).orEmpty()
        }
        if (videoSizes.isEmpty()) return null

        val previewCandidates = map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val previewSize = previewCandidates
            .filter { it.width <= 1280 && it.height <= 960 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: previewCandidates.minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return null

        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equiv = if (sensor != null && focal > 0f) {
            focal * 43.267f / hypot(sensor.width, sensor.height)
        } else 26f   // 情報が取れないレンズは広角扱い
        val fov = if (sensor != null && focal > 0f) {
            Math.toDegrees(2.0 * atan(sensor.width.toDouble() / (2.0 * focal))).toFloat()
        } else 0f
        val pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?.let { it.width.toLong() * it.height.toLong() } ?: 0L

        return Raw(id, focal, equiv, fov, pixels, videoSizes, previewSize, cameraId, isPhysicalStream)
    }

    /** 1倍未満は0.1刻み、1倍以上は0.5刻みに丸めて表示する。 */
    private fun zoomText(z: Float): String {
        val s = if (z < 1f) (z * 10).roundToInt() / 10f else (z * 2).roundToInt() / 2f
        return if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"
    }

    /** 端末がアプリに公開しているカメラの一覧（検出できないときの原因調査用）。 */
    fun diagnostics(context: Context): String = buildString {
        val manager = context.getSystemService(CameraManager::class.java)
        fun line(id: String, indent: String) {
            val c = manager.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "背面"
                CameraCharacteristics.LENS_FACING_FRONT -> "前面"
                else -> "その他"
            }
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val eq = if (sensor != null && f > 0f) (f * 43.267f / hypot(sensor.width, sensor.height)).roundToInt() else -1
            val logical = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            val nRec = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaRecorder::class.java)?.size ?: 0
            appendLine("$indent ID $id $facing 録画サイズ${nRec}件 焦点${"%.1f".format(f)}mm 換算${eq}mm" +
                (if (logical) " 論理マルチ物理=${c.physicalCameraIds}" else ""))
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            appendLine("同時使用できるカメラIDの組: ${manager.concurrentCameraIds}")
        }
        manager.cameraIdList.forEach { id ->
            runCatching {
                line(id, "")
                manager.getCameraCharacteristics(id).physicalCameraIds.forEach { runCatching { line(it, "  └") } }
            }
        }
    }
}
