package com.example.multicamera

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaStoreVideoExporter {
    fun export(context: Context, sourceFiles: List<File>): List<String> {
        return sourceFiles.mapNotNull { source ->
            runCatching { exportOne(context, source) }.getOrNull()
        }
    }

    private fun exportOne(context: Context, source: File): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/MultiCam"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore non ha creato il file ${source.name}")

        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: error("Impossibile aprire ${source.name} in MediaStore")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Movies/MultiCam/${source.name}"
    }
}
