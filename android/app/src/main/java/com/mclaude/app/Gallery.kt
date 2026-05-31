package com.mclaude.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 다운로드한 이미지를 폰 갤러리(사진 앱)의 'MJGallery' 앨범에 추가 */
object Gallery {

    const val ALBUM = "MJGallery"

    fun save(ctx: Context, file: File, displayName: String, mime: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(ctx, file, displayName, mime)
            else saveLegacy(ctx, file, displayName)
        } catch (_: Exception) {
            false
        }
    }

    /** Android 10+ : MediaStore (권한 불필요) */
    private fun saveScoped(ctx: Context, file: File, name: String, mime: String): Boolean {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri).use { out ->
            if (out == null) return false
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    /** Android 9 이하 : 공용 Pictures 폴더 + 미디어 스캔 (WRITE_EXTERNAL_STORAGE 필요) */
    private fun saveLegacy(ctx: Context, file: File, name: String): Boolean {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, name)
        file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
        return true
    }
}
