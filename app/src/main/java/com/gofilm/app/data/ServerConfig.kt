package com.gofilm.app.data

import com.gofilm.app.BuildConfig

/**
 * 统一域名与短路径。
 *
 * 对外只暴露子域名 + 短路径，不暴露 /api、/app/ytdlp 等内部结构。
 * Nginx 配置见 server/nginx/alucard-domains.conf。
 */
object ServerConfig {

    val apiOrigin: String
        get() = BuildConfig.API_ORIGIN.trimEnd('/')

    val defaultBaseUrl: String
        get() = BuildConfig.DEFAULT_BASE_URL

    val homeUrl: String
        get() = BuildConfig.HOME_URL

    val downloadUrl: String
        get() = BuildConfig.DOWNLOAD_URL

    val adsUrl: String
        get() = BuildConfig.ADS_URL

    val otaOrigin: String
        get() = BuildConfig.OTA_ORIGIN.trimEnd('/')

    val versionUrl: String
        get() = BuildConfig.VERSION_URL

    /** 从用户配置的 baseUrl 推导 API origin（兼容旧版 …/api/） */
    fun originFromBaseUrl(baseUrl: String): String {
        return baseUrl
            .trim()
            .trimEnd('/')
            .removeSuffix("/api")
            .trimEnd('/')
            .ifBlank { apiOrigin }
    }

    /** 埋点 POST */
    fun statsEventUrl(baseUrl: String = defaultBaseUrl): String =
        "${originFromBaseUrl(baseUrl)}/e"

    /** 链接解析 POST */
    fun ytdlpResolveUrl(baseUrl: String = defaultBaseUrl): String =
        "${originFromBaseUrl(baseUrl)}/r"

    /** 服务端代下 POST */
    fun ytdlpDownloadUrl(baseUrl: String = defaultBaseUrl): String =
        "${originFromBaseUrl(baseUrl)}/d"

    /** 磁力种子代理 */
    fun magnetProxyUrl(infoHashHex: String, baseUrl: String = defaultBaseUrl): String {
        val h = infoHashHex.lowercase()
        return "${originFromBaseUrl(baseUrl)}/t/$h.torrent"
    }

    /** 拼接服务端返回的相对 file_url */
    fun absoluteApiUrl(pathOrUrl: String, baseUrl: String = defaultBaseUrl): String {
        val p = pathOrUrl.trim()
        if (p.startsWith("http://") || p.startsWith("https://")) return p
        val origin = originFromBaseUrl(baseUrl)
        return if (p.startsWith("/")) "$origin$p" else "$origin/$p"
    }
}
