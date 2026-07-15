package com.gofilm.app.data.repo

import com.gofilm.app.data.api.ApiClient
import com.gofilm.app.data.api.FilmApi
import com.gofilm.app.data.dto.ApiResponse
import com.gofilm.app.data.dto.BasicConfig
import com.gofilm.app.data.dto.CategoryItem
import com.gofilm.app.data.dto.ClassifyData
import com.gofilm.app.data.dto.ClassifySearchData
import com.gofilm.app.data.dto.FilmCard
import com.gofilm.app.data.dto.FilmDetail
import com.gofilm.app.data.dto.FilmDetailData
import com.gofilm.app.data.dto.IndexData
import com.gofilm.app.data.dto.PlayInfoData
import com.gofilm.app.data.dto.SearchData
import com.gofilm.app.data.local.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FilmRepository(
    private val settingsStore: SettingsStore
) {
    val baseUrlFlow: Flow<String> = settingsStore.baseUrlFlow

    private val mutex = Mutex()
    private var cachedBaseUrl: String? = null
    private var api: FilmApi? = null

    private suspend fun api(): FilmApi = mutex.withLock {
        val url = settingsStore.baseUrlFlow.first()
        if (api == null || cachedBaseUrl != url) {
            cachedBaseUrl = url
            api = ApiClient.create(url)
        }
        api!!
    }

    suspend fun currentBaseUrl(): String = settingsStore.baseUrlFlow.first()

    suspend fun setBaseUrl(url: String) {
        settingsStore.setBaseUrl(url)
        mutex.withLock {
            cachedBaseUrl = null
            api = null
        }
    }

    suspend fun testConnection(): Result<BasicConfig> = safe {
        val resp = api().basicConfig()
        require(resp.code == 0 && resp.data != null) {
            resp.msg.ifBlank { "连接失败 code=${resp.code}" }
        }
        resp.data!!
    }

    private val indexJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    suspend fun index(): Result<IndexData> = safe {
        // 用原始 JSON + 与客户端一致的 Json 配置解析，避免字段类型差异导致整页失败
        val raw = api().indexRaw().string()
        val resp = indexJson.decodeFromString(ApiResponse.serializer(IndexData.serializer()), raw)
        if (resp.code != 0 || resp.data == null) {
            error(resp.msg.ifBlank { "首页加载失败 code=${resp.code}" })
        }
        resp.data!!
    }
    suspend fun navCategory(): Result<List<CategoryItem>> = unwrap { api().navCategory() }

    suspend fun filmDetail(id: Long): Result<FilmDetailData> = safe {
        val raw = api().filmDetailRaw(id).string()
        decodeApi(raw, FilmDetailData.serializer(), "影片详情")
    }

    /**
     * 播放信息：优先 filmPlayInfo；若空 body / 失败则回落到 filmDetail，
     * 从 list 里按线路+集数拼出 current，避免 JSON EOF 直接挂掉。
     */
    suspend fun filmPlayInfo(id: Long, playFrom: String, episode: Int): Result<PlayInfoData> = safe {
        try {
            val raw = api().filmPlayInfoRaw(id, playFrom, episode).string()
            if (raw.isNotBlank()) {
                val info = decodeApi(raw, PlayInfoData.serializer(), "播放信息")
                val link = info.current?.link.orEmpty()
                if (link.isNotBlank()) return@safe info
                // current 无链接时仍可能带 detail，继续尝试从 detail 取链
                resolvePlayFromDetail(info.detail, playFrom, episode)?.let { return@safe it }
            }
        } catch (_: Exception) {
            // fall through to detail
        }
        val detailData = filmDetail(id).getOrElse { throw it }
        resolvePlayFromDetail(detailData.detail, playFrom, episode)
            ?: error("该影片暂无可用播放地址")
    }

    private fun resolvePlayFromDetail(
        detail: FilmDetail?,
        playFrom: String,
        episode: Int
    ): PlayInfoData? {
        if (detail == null) return null
        val sources = detail.list.orEmpty()
        if (sources.isEmpty()) {
            val pl = detail.playList.orEmpty()
            if (pl.isEmpty()) return null
            val ep = episode.coerceIn(0, pl.lastIndex)
            val cur = pl[ep]
            if (cur.link.isBlank()) return null
            return PlayInfoData(
                current = cur,
                currentEpisode = ep,
                currentPlayFrom = playFrom.ifBlank { "main" },
                detail = detail,
                relate = null
            )
        }
        val source = sources.firstOrNull { it.id == playFrom }
            ?: sources.firstOrNull { it.name == playFrom }
            ?: sources.first()
        val links = source.linkList
        if (links.isEmpty()) return null
        val ep = episode.coerceIn(0, links.lastIndex)
        val cur = links[ep]
        if (cur.link.isBlank()) return null
        return PlayInfoData(
            current = cur,
            currentEpisode = ep,
            currentPlayFrom = source.id.ifBlank { playFrom },
            detail = detail,
            relate = null
        )
    }

    suspend fun search(keyword: String): Result<List<FilmCard>> = safe {
        val q = keyword.trim()
        if (q.isEmpty()) return@safe emptyList()
        val raw = api().searchFilmRaw(q).string()
        if (raw.isBlank()) error("搜索接口无响应，请稍后重试")
        // 兼容 data 为 {list,page} / 直接数组 / null
        val root = indexJson.parseToJsonElement(raw).jsonObject
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        val msg = root["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val dataEl = root["data"]
        val list: List<FilmCard> = when {
            dataEl == null || dataEl is JsonNull -> emptyList()
            dataEl is JsonArray ->
                indexJson.decodeFromJsonElement(ListSerializer(FilmCard.serializer()), dataEl)
            dataEl is JsonObject -> {
                val listEl = dataEl["list"]
                if (listEl != null && listEl !is JsonNull) {
                    indexJson.decodeFromJsonElement(ListSerializer(FilmCard.serializer()), listEl)
                } else emptyList()
            }
            else -> emptyList()
        }
        // 无结果不抛错，交给 UI 显示空态（msg 仅在调试需要时可用）
        if (code != 0 && list.isEmpty() && msg.isNotBlank() && !msg.contains("暂无")) {
            // 真正错误才抛
            error(msg)
        }
        list
    }

    suspend fun filmClassify(pid: Long): Result<ClassifyData> = unwrap { api().filmClassify(pid) }

    /** @param categoryCid 二级分类 id，空串表示全部 */
    suspend fun filmClassifySearch(
        pid: Long,
        categoryCid: String = "",
        current: Int = 1
    ): Result<ClassifySearchData> =
        unwrap {
            api().filmClassifySearch(
                pid = pid,
                category = categoryCid,
                current = current
            )
        }

    private fun <T> decodeApi(
        raw: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        label: String
    ): T {
        if (raw.isBlank()) {
            error("$label 返回空数据，请稍后重试")
        }
        val resp = try {
            indexJson.decodeFromString(ApiResponse.serializer(serializer), raw)
        } catch (e: Exception) {
            val preview = raw.take(120).replace("\n", " ")
            error("$label 解析失败：${e.message?.take(80) ?: "JSON 错误"}。响应预览：$preview")
        }
        if (resp.code != 0 || resp.data == null) {
            error(resp.msg.ifBlank { "$label 失败 code=${resp.code}" })
        }
        return resp.data!!
    }

    private suspend fun <T> unwrap(block: suspend () -> ApiResponse<T>): Result<T> = safe {
        val resp = block()
        if (resp.code != 0 || resp.data == null) {
            error(resp.msg.ifBlank { "请求失败 code=${resp.code}" })
        }
        resp.data!!
    }

    private suspend fun <T> safe(block: suspend () -> T): Result<T> = runCatching {
        block()
    }.recoverCatching { e ->
        throw Exception(friendlyError(e), e)
    }

    private fun friendlyError(e: Throwable): String {
        val msg = e.message.orEmpty()
        if (msg.contains("EOF", ignoreCase = true) ||
            msg.contains("Expected start of the object", ignoreCase = true)
        ) {
            return "服务暂时无响应，请稍后重试"
        }
        return when (e) {
            is HttpException -> when (e.code()) {
                404 -> "资源不存在或接口暂不可用"
                502, 503 -> "服务未就绪，请稍后重试"
                else -> "网络错误（${e.code()}）"
            }
            is SocketTimeoutException -> "连接超时，请检查网络"
            is UnknownHostException -> "无法连接服务，请检查网络"
            is IOException -> "网络错误：${e.message ?: "连接失败"}"
            else -> e.message ?: "未知错误"
        }
    }
}
