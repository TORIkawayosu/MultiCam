package com.example.multicamera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/** 録画の出力先。MediaStore のエントリに、録画しながら直接書き込む。 */
class VideoOutput(
    val uri: Uri,
    val pfd: ParcelFileDescriptor,
    val displayName: String
) {
    val publicPath: String get() = "${Environment.DIRECTORY_MOVIES}/MultiCam/$displayName"
}

/**
 * 指定ボリューム（本体 / SDカード）の Movies/MultiCam に、動画ファイルを直接作る。
 * 録画中は IS_PENDING=1 で他のアプリから隠し、正常に終われば公開する。コピーは行わない。
 */
object MediaStoreVideoWriter {

    fun create(context: Context, volumeName: String, displayName: String): VideoOutput {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MultiCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(volumeName), values)
            ?: error("MediaStore がファイルを作成できませんでした: $displayName")
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        } ?: run {
            runCatching { resolver.delete(uri, null, null) }
            error("出力ファイルを開けませんでした: $displayName")
        }
        return VideoOutput(uri, pfd, displayName)
    }

    /** keep=true なら公開、false なら削除する。公開できたかを返す。 */
    fun finish(context: Context, output: VideoOutput, keep: Boolean): Boolean {
        runCatching { output.pfd.close() }
        val resolver = context.contentResolver
        if (!keep) {
            runCatching { resolver.delete(output.uri, null, null) }
            return false
        }
        return runCatching {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(output.uri, values, null, null) > 0
        }.getOrDefault(false)
    }
}
