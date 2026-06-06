package com.example.multicamera

enum class QualityPreference(val label: String) {
    MAX("Max"),
    UHD("4K"),
    FHD("1080p"),
    HD("720p")
}

enum class FpsPreference(val value: Int) {
    FPS_30(30),
    FPS_60(60)
}

data class DualVideoSettings(
    val ultraQuality: QualityPreference = QualityPreference.MAX,
    val ultraFps: FpsPreference = FpsPreference.FPS_30,
    val wideQuality: QualityPreference = QualityPreference.MAX,
    val wideFps: FpsPreference = FpsPreference.FPS_30
)
