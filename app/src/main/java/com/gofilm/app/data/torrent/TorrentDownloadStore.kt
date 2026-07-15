package com.gofilm.app.data.torrent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class TorrentDownloadRecord(
    val id: String,
    val torrentName: String,
    val fileName: String,
    val fileIndex: Int,
    val filePath: String,
    val totalBytes: Long,
    val doneBytes: Long = 0,
    val progress: Float = 0f,
    val state: String = "QUEUED",
    val downloadRate: Int = 0,
    val numPeers: Int = 0,
    val isFinished: Boolean = false,
    /** 当前是否在引擎中下载 */
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 本地保存的 .torrent 路径，便于再次打开 */
    val torrentFilePath: String = "",
    /** info-hash 十六进制，用于去重 */
    val infoHashHex: String = "",
    val error: String? = null
)

/**
 * 种子下载记录（进程内 + SharedPreferences 持久化）。
 * 引擎进度会写回这里；离开页面不清理。
 */
object TorrentDownloadStore {
    private const val PREF = "torrent_downloads"
    private const val KEY = "records_v1"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _records = MutableStateFlow<List<TorrentDownloadRecord>>(emptyList())
    val records: StateFlow<List<TorrentDownloadRecord>> = _records.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

    fun activeRecord(): TorrentDownloadRecord? {
        val id = _activeId.value ?: return null
        return _records.value.firstOrNull { it.id == id }
    }

    fun findByHashAndIndex(infoHashHex: String, fileIndex: Int): TorrentDownloadRecord? {
        if (infoHashHex.isBlank()) return null
        val key = infoHashHex.lowercase()
        return _records.value.firstOrNull {
            it.infoHashHex.equals(key, ignoreCase = true) && it.fileIndex == fileIndex
        }
    }

    fun findFinishedWithFile(infoHashHex: String, fileIndex: Int): TorrentDownloadRecord? {
        val rec = findByHashAndIndex(infoHashHex, fileIndex) ?: return null
        if (!rec.isFinished) return null
        val f = File(rec.filePath)
        return if (f.exists() && f.length() > 0L) rec else null
    }

    fun create(
        torrentName: String,
        fileName: String,
        fileIndex: Int,
        filePath: String,
        totalBytes: Long,
        torrentFilePath: String,
        infoHashHex: String = "",
        id: String = UUID.randomUUID().toString()
    ): TorrentDownloadRecord {
        // 同 hash+index 已存在则更新为 active，不新建重复记录
        val existing = findByHashAndIndex(infoHashHex, fileIndex)
        if (existing != null) {
            val updated = existing.copy(
                torrentName = torrentName,
                fileName = fileName,
                filePath = filePath,
                totalBytes = if (totalBytes > 0) totalBytes else existing.totalBytes,
                torrentFilePath = torrentFilePath.ifBlank { existing.torrentFilePath },
                infoHashHex = infoHashHex.ifBlank { existing.infoHashHex },
                state = if (existing.isFinished) existing.state else "STARTING",
                isActive = !existing.isFinished,
                updatedAt = System.currentTimeMillis()
            )
            _records.update { list ->
                list.map {
                    when {
                        it.id == existing.id -> updated
                        else -> it.copy(isActive = false)
                    }
                }
            }
            if (!updated.isFinished) _activeId.value = updated.id
            persist()
            return updated
        }

        val rec = TorrentDownloadRecord(
            id = id,
            torrentName = torrentName,
            fileName = fileName,
            fileIndex = fileIndex,
            filePath = filePath,
            totalBytes = totalBytes,
            torrentFilePath = torrentFilePath,
            infoHashHex = infoHashHex.lowercase(),
            state = "STARTING",
            isActive = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        // 同一时间只标一个 active
        _records.update { list ->
            list.map { it.copy(isActive = false) } + rec
        }
        _activeId.value = rec.id
        persist()
        return rec
    }

    fun updateProgress(
        id: String,
        doneBytes: Long,
        totalBytes: Long,
        progress: Float,
        state: String,
        downloadRate: Int,
        numPeers: Int,
        isFinished: Boolean,
        error: String? = null
    ) {
        _records.update { list ->
            list.map { r ->
                if (r.id != id) r
                else r.copy(
                    doneBytes = doneBytes,
                    totalBytes = if (totalBytes > 0) totalBytes else r.totalBytes,
                    progress = progress,
                    state = state,
                    downloadRate = downloadRate,
                    numPeers = numPeers,
                    isFinished = isFinished,
                    isActive = !isFinished && _activeId.value == id,
                    error = error,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        if (isFinished && _activeId.value == id) {
            // 完成后仍保留 activeId 便于回到页面继续播，但 isActive=false 表示不必显示「下载中」抢占
        }
        // 节流写入：每 2 秒或完成时
        val now = System.currentTimeMillis()
        if (isFinished || now - lastPersistMs > 2000) {
            persist()
            lastPersistMs = now
        }
    }

    private var lastPersistMs = 0L

    fun markStopped(id: String) {
        _records.update { list ->
            list.map {
                if (it.id != id) it
                else if (it.isFinished) {
                    // 已完成的任务绝不标成 STOPPED，永久保留
                    it.copy(isActive = false, state = "FINISHED", updatedAt = System.currentTimeMillis())
                } else {
                    it.copy(
                        isActive = false,
                        state = "STOPPED",
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
        }
        if (_activeId.value == id) _activeId.value = null
        persist()
    }

    fun markFinished(id: String, filePath: String? = null) {
        _records.update { list ->
            list.map {
                if (it.id != id) it
                else it.copy(
                    isFinished = true,
                    isActive = false,
                    progress = 1f,
                    doneBytes = it.totalBytes.coerceAtLeast(it.doneBytes),
                    state = "FINISHED",
                    filePath = filePath?.takeIf { p -> p.isNotBlank() } ?: it.filePath,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        persist()
    }

    fun setActiveId(id: String?) {
        _activeId.value = id
        _records.update { list ->
            list.map { it.copy(isActive = id != null && it.id == id && !it.isFinished) }
        }
        persist()
    }

    fun remove(id: String) {
        val rec = _records.value.firstOrNull { it.id == id }
        _records.update { it.filterNot { r -> r.id == id } }
        if (_activeId.value == id) _activeId.value = null
        // 不删视频文件，只删记录；torrent 元数据可删
        try {
            rec?.torrentFilePath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        } catch (_: Throwable) {
        }
        persist()
    }

    fun clearFinished() {
        _records.update { list -> list.filter { !it.isFinished && it.state != "STOPPED" } }
        persist()
    }

    fun saveTorrentBytes(context: Context, id: String, bytes: ByteArray): String {
        val dir = File(context.filesDir, "torrent_meta").apply { mkdirs() }
        val f = File(dir, "$id.torrent")
        f.writeBytes(bytes)
        return f.absolutePath
    }

    private fun load() {
        val ctx = appContext ?: return
        try {
            val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            if (raw.isNullOrBlank()) return
            val list = json.decodeFromString<List<TorrentDownloadRecord>>(raw)
            // 冷启动：已完成永久保留；未完成标为 STOPPED；校验文件是否还在
            _records.value = list.map { rec ->
                if (rec.isFinished) {
                    val ok = try {
                        File(rec.filePath).exists()
                    } catch (_: Throwable) {
                        false
                    }
                    rec.copy(
                        isActive = false,
                        state = if (ok) "FINISHED" else "MISSING",
                        isFinished = ok
                    )
                } else {
                    rec.copy(isActive = false, state = "STOPPED")
                }
            }
            _activeId.value = null
        } catch (_: Throwable) {
            _records.value = emptyList()
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        try {
            // 已完成优先保留，最多 80 条
            val list = _records.value
                .sortedWith(
                    compareByDescending<TorrentDownloadRecord> { it.isFinished }
                        .thenByDescending { it.updatedAt }
                )
                .take(80)
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, json.encodeToString(list))
                .apply()
        } catch (_: Throwable) {
        }
    }
}
