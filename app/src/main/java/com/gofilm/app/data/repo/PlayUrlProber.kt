package com.gofilm.app.data.repo

import com.gofilm.app.data.local.PlayabilityCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 探测 m3u8 是否可用。走服务器 /proxy，与播放器一致。
 */
class PlayUrlProber(
    private val playabilityCache: PlayabilityCache
) {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * @return true 可播；false 明确失效（404/空体等）；null 网络异常未知
     */
    suspend fun probeRawUrl(rawUrl: String, apiBaseUrl: String): Boolean? = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (url.isEmpty()) return@withContext false
        val target = toProxyUrl(url, apiBaseUrl)
        try {
            val req = Request.Builder()
                .url(target)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                if (code == 404 || code == 410) return@withContext false
                if (code == 403 || code == 401) return@withContext false
                if (!resp.isSuccessful) return@withContext null
                val peek = resp.body?.source()?.let { src ->
                    src.request(256)
                    src.buffer.clone().readUtf8()
                }.orEmpty()
                when {
                    peek.contains("#EXTM3U") -> true
                    peek.isBlank() -> false
                    peek.contains("404") || peek.contains("Not Found") -> false
                    // 非 m3u8 但 200，可能是 mp4 直链
                    code == 200 && peek.length > 10 -> true
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 探测一部片的多条线路（每线路取前几集）。
     * 返回仍可用的 (sourceId, episodeIndex) 列表；并更新本地缓存。
     */
    suspend fun probeFilm(
        filmId: Long,
        apiBaseUrl: String,
        sources: List<Pair<String, List<String>>> // sourceId to episode links
    ): ProbeResult {
        if (sources.isEmpty()) {
            playabilityCache.markDead(filmId)
            return ProbeResult(allDead = true, deadSourceIds = emptySet(), deadLinks = emptySet())
        }
        val deadSources = mutableSetOf<String>()
        val deadLinks = mutableSetOf<String>()
        var anyOk = false

        for ((sid, links) in sources) {
            if (links.isEmpty()) {
                deadSources.add(sid)
                continue
            }
            // 每条线路最多探测前 2 集，避免卡太久
            val sample = links.take(2)
            var sourceOk = false
            for (link in sample) {
                when (probeRawUrl(link, apiBaseUrl)) {
                    true -> {
                        sourceOk = true
                        anyOk = true
                        break
                    }
                    false -> deadLinks.add(link)
                    null -> { /* 未知，不立刻判死 */ }
                }
            }
            // 样本全 404 则整条线路视为失效
            if (!sourceOk && sample.isNotEmpty() && sample.all { it in deadLinks }) {
                deadSources.add(sid)
            }
        }

        if (anyOk) {
            playabilityCache.markOk(filmId)
        } else if (deadSources.size == sources.size || sources.all { it.second.isEmpty() }) {
            playabilityCache.markDead(filmId)
        }

        return ProbeResult(
            allDead = !anyOk && deadSources.size >= sources.size,
            deadSourceIds = deadSources,
            deadLinks = deadLinks
        )
    }

    data class ProbeResult(
        val allDead: Boolean,
        val deadSourceIds: Set<String>,
        val deadLinks: Set<String>
    )

    companion object {
        fun toProxyUrl(mediaUrl: String, apiBaseUrl: String): String {
            val raw = mediaUrl.trim()
            if (raw.contains("/proxy") && raw.contains("url=")) return raw
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) return raw
            val origin = apiBaseUrl
                .removeSuffix("/")
                .removeSuffix("/api")
                .trimEnd('/')
            val encoded = URLEncoder.encode(raw, Charsets.UTF_8.name())
            return "$origin/proxy/index.m3u8?url=$encoded"
        }
    }
}
