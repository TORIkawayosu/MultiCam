package com.example.multicamera

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/** 録画の一時保存先（dir）と、最終的に公開する MediaStore のボリューム。 */
data class SaveTarget(
    val dir: File,
    val mediaStoreVolume: String,
    val label: String
)

data class SdCard(
    val dir: File,              // SDカード上のアプリ専用 Movies フォルダ
    val mediaStoreVolume: String,
    val description: String
)

object StorageHelper {

    /** マウント済みのSDカード（取り外し可能な非プライマリ ボリューム）を探す。 */
    fun findSdCard(context: Context): SdCard? {
        val sm = context.getSystemService(StorageManager::class.java)
        context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).forEach { dir ->
            if (dir == null) return@forEach
            val vol = sm.getStorageVolume(dir) ?: return@forEach
            if (!vol.isRemovable || vol.isPrimary || vol.state != Environment.MEDIA_MOUNTED) return@forEach
            val rawName: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                vol.mediaStoreVolumeName
            } else {
                vol.uuid?.lowercase(Locale.ROOT)
            }
            val name = rawName ?: return@forEach
            return SdCard(dir, name, vol.getDescription(context))
        }
        return null
    }

    fun internalTarget(context: Context): SaveTarget {
        val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return SaveTarget(
            dir = File(movies, "MultiCam"),
            mediaStoreVolume = MediaStore.VOLUME_EXTERNAL_PRIMARY,
            label = context.getString(R.string.label_internal_storage)
        )
    }

    fun sdTarget(context: Context, sd: SdCard) = SaveTarget(
        dir = File(sd.dir, "MultiCam"),
        mediaStoreVolume = sd.mediaStoreVolume,
        label = context.getString(R.string.label_sd_card)
    )
}
