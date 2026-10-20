package com.example.multicamera

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** アプリ専用領域の動画を、指定ボリューム（本体 / SDカード）の Movies/MultiCam に公開する。 */
object MediaStoreVideoExporter {

    /** 成功したものだけ「元ファイル → 公開パス」で返す。 */
    fun export(context: Context, sourceFiles: List<File>, volumeName: String): List<Pair<File, String>> {
        return sourceFiles.mapNotNull { source ->
            runCatching { source to exportOne(context, source, volumeName) }.getOrNull()
        }
    }

    private fun exportOne(context: Context, source: File, volumeName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MultiCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(volumeName)
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore がファイルを作成できませんでした: ${source.name}")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("MediaStore の出力を開けませんでした: ${source.name}")

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }   // 中途半端なファイルを残さない
            throw t
        }
        return "Movies/MultiCam/${source.name}"
    }
}
