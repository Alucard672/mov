package com.gofilm.app.data.torrent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 边下边播专用 DataSource：
 * - 长度用种子元数据（不是半截文件）
 * - 读之前等 libtorrent 片齐，避免 sparse 洞读出 0 导致解封装失败
 * - 需要某段数据时主动提高 piece 优先级
 */
@UnstableApi
class TorrentStreamDataSource(
    private val file: File,
    private val totalSize: Long
) : BaseDataSource(/* isNetwork= */ false) {

    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var readPosition: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val size = totalSize.coerceAtLeast(0L)
        val start = dataSpec.position.coerceAtLeast(0L)
        if (start > size) {
            throw EOFException("position $start > size $size")
        }
        val length = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> size - start
        }
        bytesRemaining = length.coerceAtMost(size - start)
        readPosition = start

        // 开播/seek 时预拉这一段（含 MP4 读尾部 moov）
        val waitLen = bytesRemaining.coerceAtMost(256L * 1024).coerceAtLeast(1)
        if (!TorrentEngine.waitForSelectedFileRange(start, waitLen, timeoutMs = 25_000L)) {
            // 仍打开文件，后续 read 再等；首包失败会再重试
        }

        raf = RandomAccessFile(file, "r").also { it.seek(start) }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = length.toLong().coerceAtMost(bytesRemaining).toInt()
        // 等当前窗口片齐（最长 20s）；洞读 0 会导致 Exo 解封装失败
        val ok = TorrentEngine.waitForSelectedFileRange(
            offsetInFile = readPosition,
            length = toRead.toLong(),
            timeoutMs = 20_000L
        )
        if (!ok) {
            throw IOException(
                "边下边播：等待分片超时 pos=$readPosition len=$toRead " +
                    "(peers/速度正常时会自动重试)"
            )
        }

        val n = try {
            raf!!.read(buffer, offset, toRead)
        } catch (e: Exception) {
            throw IOException("读本地种子文件失败: ${e.message}", e)
        }
        if (n < 0) {
            if (bytesRemaining > 0) throw EOFException()
            return C.RESULT_END_OF_INPUT
        }
        readPosition += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            raf?.close()
        } catch (_: Throwable) {
        }
        raf = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(
        private val file: File,
        private val totalSize: Long
    ) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): androidx.media3.datasource.DataSource {
            return TorrentStreamDataSource(file, totalSize)
        }
    }
}
