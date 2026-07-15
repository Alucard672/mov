package com.gofilm.app.data.link

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * 把已下载的视频/图片写入系统相册（Movies/Pictures/Alucard）。
 */
object GallerySaver {

    fun saveVideoToGallery(context: Context, file: File, displayName: String? = null): Result<Uri> {
        if (!file.exists() || file.length() <= 0L) {
            return Result.failure(Exception("文件不存在或为空"))
        }
        val name = (displayName ?: file.name)
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .ifBlank { "video_${System.currentTimeMillis()}.mp4" }
        val mime = mimeFromName(name)
        val isImage = mime.startsWith("image/")

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, file, name, mime, isImage)
            } else {
                saveLegacy(context, file, name, isImage)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        file: File,
        name: String,
        mime: String,
        isImage: Boolean
    ): Result<Uri> {
        val collection = if (isImage) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val relative = if (isImage) {
            Environment.DIRECTORY_PICTURES + "/Alucard"
        } else {
            Environment.DIRECTORY_MOVIES + "/Alucard"
        }
        val values = ContentValues().apply {
            if (isImage) {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, relative)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, relative)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: return Result.failure(Exception("无法创建相册条目"))
        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: return Result.failure(Exception("无法写入相册"))
            values.clear()
            if (isImage) {
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
            } else {
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
            return Result.success(uri)
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            return Result.failure(t)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        file: File,
        name: String,
        isImage: Boolean
    ): Result<Uri> {
        val base = if (isImage) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }
        val dir = File(base, "Alucard").apply { mkdirs() }
        var dest = File(dir, name)
        if (dest.exists()) {
            dest = File(dir, "${System.currentTimeMillis()}_$name")
        }
        file.copyTo(dest, overwrite = true)
        // 通知图库
        val values = ContentValues().apply {
            if (isImage) {
                put(MediaStore.Images.Media.DATA, dest.absolutePath)
                put(MediaStore.Images.Media.MIME_TYPE, mimeFromName(name))
            } else {
                put(MediaStore.Video.Media.DATA, dest.absolutePath)
                put(MediaStore.Video.Media.MIME_TYPE, mimeFromName(name))
            }
        }
        val collection = if (isImage) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: Uri.fromFile(dest)
        return Result.success(uri)
    }

    private fun mimeFromName(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".mp4") || n.endsWith(".m4v") -> "video/mp4"
            n.endsWith(".webm") -> "video/webm"
            n.endsWith(".mkv") -> "video/x-matroska"
            n.endsWith(".mov") -> "video/quicktime"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".mp3") -> "audio/mpeg"
            else -> "video/mp4"
        }
    }
}
