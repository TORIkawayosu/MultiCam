package com.example.multicamera

import android.util.Size
import androidx.annotation.StringRes

enum class QualityPreference(@StringRes val labelRes: Int) {
    MAX(R.string.quality_max),
    UHD(R.string.quality_uhd),
    FHD(R.string.quality_fhd),
    HD(R.string.quality_hd)
}

enum class FpsPreference(val value: Int) {
    FPS_30(30),
    FPS_60(60)
}

/** 音声をどのファイルに録音するか。 */
enum class AudioPreference(@StringRes val labelRes: Int) {
    ALL(R.string.audio_all),
    FIRST_ONLY(R.string.audio_first),
    OFF(R.string.audio_off)
}

/** 保存先の選択。 */
enum class StorageChoice { INTERNAL, SD }

/**
 * 録画設定。画質はレンズごと、fps は全レンズ共通
 * （1つのキャプチャリクエストで動かすため）。
 */
data class VideoSettings(
    val fps: FpsPreference = FpsPreference.FPS_60,
    val audio: AudioPreference = AudioPreference.ALL,
    val qualities: Map<String, QualityPreference> = emptyMap()
) {
    fun qualityFor(physicalId: String): QualityPreference =
        qualities[physicalId] ?: QualityPreference.FHD
}

enum class LensType(val tag: String) {
    ULTRA_WIDE("ultrawide"),
    WIDE("wide"),
    TELE("tele")
}

/** 検出された背面の物理レンズ1本分の情報。 */
data class LensInfo(
    val physicalId: String,
    /** 実際に開くカメラID。論理カメラ配下の物理レンズなら論理カメラのID、単独のカメラなら自分のID。 */
    val cameraId: String,
    /** true: 論理カメラの物理ストリームとして取得 / false: 単独のカメラとして開く */
    val isPhysicalStream: Boolean,
    val type: LensType,
    val zoomRatio: Float,
    val label: String,        // 例: 「超広角 0.6x」
    val detail: String,       // 例: 「ID 2 ・ 焦点 2.2 mm …」
    val fileTag: String,      // ファイル名用。例: ultrawide_0_6x
    val videoSizes: List<Size>,
    val previewSize: Size,
    /** プレビューに使える出力サイズの一覧（録画の縦横比に合わせて選ぶ）。 */
    val previewSizes: List<Size>,
    /** センサーの向き（度）。プレビューと録画の回転補正に使う。 */
    val sensorOrientation: Int
)

data class LensPlan(
    val logicalCameraId: String,
    val lenses: List<LensInfo>   // 画角の広い順（ズーム倍率の昇順）
)
