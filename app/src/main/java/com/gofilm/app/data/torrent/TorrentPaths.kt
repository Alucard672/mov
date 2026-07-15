package com.gofilm.app.data.torrent

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 种子/磁力下载路径：
 * - 工作目录：应用私有外部存储 Downloads/Alucard/{infoHash}/
 * - 完成后文件即在「我的下载」可访问位置（不落 cache，避免被系统清掉）
 */
object TorrentPaths {

    fun myDownloadsDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        return File(base, "Alucard").apply { mkdirs() }
    }

    fun workDir(context: Context, infoHashHex: String): File {
        val key = infoHashHex.lowercase().ifBlank { "pending" }
        return File(myDownloadsDir(context), key).apply { mkdirs() }
    }

    fun legacyTorrentsDir(context: Context): File =
        File(context.filesDir, "torrents")

    /** 将已完成文件提升到「我的下载」根目录（扁平一份，方便浏览） */
    fun promoteFinishedCopy(context: Context, source: File, displayName: String): File {
        if (!source.exists() || source.length() <= 0L) return source
        val dir = myDownloadsDir(context)
        val safe = displayName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { source.name }
        var dest = File(dir, safe)
        if (dest.absolutePath == source.absolutePath) return source
        // 同名不同内容则加后缀
        if (dest.exists() && dest.length() != source.length()) {
            val base = safe.substringBeforeLast('.', safe)
            val ext = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
            dest = File(dir, "${base}_${source.length()}$ext")
        }
        if (!dest.exists() || dest.length() != source.length()) {
            try {
                source.copyTo(dest, overwrite = true)
            } catch (_: Throwable) {
                return source
            }
        }
        return dest
    }
}
