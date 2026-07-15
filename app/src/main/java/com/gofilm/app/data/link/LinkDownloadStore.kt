package com.gofilm.app.data.link

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
data class LinkDownloadRecord(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val mediaUrl: String = "",
    val filePath: String = "",
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val progress: Float = 0f,
    val state: String = "QUEUED",
    val isFinished: Boolean = false,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object LinkDownloadStore {
    private const val PREF = "link_downloads"
    private const val KEY = "records_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _records = MutableStateFlow<List<LinkDownloadRecord>>(emptyList())
    val records: StateFlow<List<LinkDownloadRecord>> = _records.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

    fun create(title: String, sourceUrl: String, mediaUrl: String = ""): LinkDownloadRecord {
        val rec = LinkDownloadRecord(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "video" },
            sourceUrl = sourceUrl,
            mediaUrl = mediaUrl,
            state = "STARTING"
        )
        _records.update { listOf(rec) + it }
        persist()
        return rec
    }

    fun update(
        id: String,
        doneBytes: Long? = null,
        totalBytes: Long? = null,
        progress: Float? = null,
        state: String? = null,
        filePath: String? = null,
        mediaUrl: String? = null,
        title: String? = null,
        isFinished: Boolean? = null,
        error: String? = null
    ) {
        _records.update { list ->
            list.map { r ->
                if (r.id != id) r
                else r.copy(
                    doneBytes = doneBytes ?: r.doneBytes,
                    totalBytes = totalBytes ?: r.totalBytes,
                    progress = progress ?: r.progress,
                    state = state ?: r.state,
                    filePath = filePath ?: r.filePath,
                    mediaUrl = mediaUrl ?: r.mediaUrl,
                    title = title ?: r.title,
                    isFinished = isFinished ?: r.isFinished,
                    error = error,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        persist()
    }

    fun remove(id: String, deleteFile: Boolean = false) {
        val rec = _records.value.firstOrNull { it.id == id }
        if (deleteFile) {
            try {
                rec?.filePath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            } catch (_: Throwable) {
            }
        }
        _records.update { it.filterNot { r -> r.id == id } }
        persist()
    }

    private fun load() {
        val ctx = appContext ?: return
        try {
            val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            if (raw.isNullOrBlank()) return
            val list = json.decodeFromString<List<LinkDownloadRecord>>(raw)
            _records.value = list.map { rec ->
                if (rec.isFinished) {
                    val ok = try {
                        File(rec.filePath).exists()
                    } catch (_: Throwable) {
                        false
                    }
                    rec.copy(state = if (ok) "FINISHED" else "MISSING", isFinished = ok)
                } else {
                    rec.copy(state = if (rec.state == "DOWNLOADING") "STOPPED" else rec.state)
                }
            }
        } catch (_: Throwable) {
            _records.value = emptyList()
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        try {
            val list = _records.value.sortedByDescending { it.updatedAt }.take(80)
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, json.encodeToString(list))
                .apply()
        } catch (_: Throwable) {
        }
    }
}
