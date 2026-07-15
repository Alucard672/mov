package com.gofilm.app.data.link

import android.content.Context
import android.util.Log
import com.gofilm.app.BuildConfig
import com.gofilm.app.data.torrent.TorrentPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 链接下载：
 * 1) 直链（mp4/m3u8 等）→ 本机 OkHttp 下载
 * 2) 页面/分享链接 → 服务器 yt-dlp 解析出直链 → 本机下载
 */
object LinkDownloadEngine {
    private const val TAG = "LinkDownload"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val resolveClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .build()

    data class ResolveResult(
        val title: String,
        val mediaUrl: String,
        val ext: String,
        val extractor: String? = null
    )

    fun ytdlpBaseUrl(): String {
        // http://host/api/ → http://host
        return BuildConfig.DEFAULT_BASE_URL
            .trimEnd('/')
            .removeSuffix("/api")
            .trimEnd('/')
    }

    fun isLikelyDirectMedia(url: String): Boolean {
        val u = url.lowercase().substringBefore('?')
        return u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".mkv") ||
            u.endsWith(".mov") || u.endsWith(".m4v") || u.endsWith(".m3u8") ||
            u.endsWith(".mp3") || u.endsWith(".m4a") || u.endsWith(".flv") ||
            u.endsWith(".ts") || (u.contains("/video/") && u.contains(".mp4"))
    }

    /**
     * 从抖音分享文案中提取真正的 URL。
     * 例：`4.84 复制打开抖音… https://v.douyin.com/xxx/ XZZ:/ S@L.WZ …`
     */
    fun extractUrlFromShareText(text: String): String {
        val t = text.trim()
        if (t.isBlank()) return ""
        val patterns = listOf(
            Regex("""https?://v\.douyin\.com/[A-Za-z0-9_\-]+/?"""),
            Regex("""https?://www\.douyin\.com/video/\d+[^\s]*"""),
            Regex("""https?://www\.iesdouyin\.com/share/video/\d+[^\s]*"""),
            Regex("""https?://www\.tiktok\.com/[^\s]+"""),
            Regex("""https?://vm\.tiktok\.com/[A-Za-z0-9]+/?"""),
            Regex("""https?://[^\s"'<>]+""")
        )
        for (p in patterns) {
            val m = p.find(t) ?: continue
            var u = m.value.trimEnd('.', ',', ';', '，', '。', '、', '）', ')', ']', '}', '\'', '"')
            u = u.replace(Regex("""\s+"""), "")
            if (u.startsWith("http")) return u
        }
        return if (t.startsWith("http://") || t.startsWith("https://")) t else ""
    }

    suspend fun resolve(rawInput: String): Result<ResolveResult> = withContext(Dispatchers.IO) {
        try {
            val url = extractUrlFromShareText(rawInput)
            if (url.isBlank()) {
                return@withContext Result.failure(
                    Exception("未识别到链接。请粘贴完整分享内容（含 https://v.douyin.com/…）")
                )
            }
            Log.i(TAG, "resolve extracted=$url from len=${rawInput.length}")

            if (isLikelyDirectMedia(url)) {
                val name = guessNameFromUrl(url)
                return@withContext Result.success(
                    ResolveResult(title = name, mediaUrl = url, ext = extFromUrl(url))
                )
            }
            // 先 HEAD 看 Content-Type（仅非抖音短链，短链多为 HTML）
            if (!url.contains("douyin.com", ignoreCase = true) &&
                !url.contains("tiktok.com", ignoreCase = true)
            ) {
                val head = probe(url)
                if (head != null && isVideoContentType(head.contentType)) {
                    return@withContext Result.success(
                        ResolveResult(
                            title = head.filename ?: guessNameFromUrl(url),
                            mediaUrl = head.finalUrl,
                            ext = extFromContentType(head.contentType) ?: extFromUrl(head.finalUrl)
                        )
                    )
                }
            }
            // 服务端解析（抖音分享页专用 + yt-dlp）
            // 把完整分享文案也传过去，服务端会再抽一次 URL
            val resolved = resolveViaYtdlp(rawInput)
            if (resolved != null) return@withContext Result.success(resolved)

            val fromHtml = extractFromHtml(url)
            if (fromHtml != null) return@withContext Result.success(fromHtml)

            Result.failure(Exception("无法解析该链接。直链可用；抖音请贴完整分享文案。"))
        } catch (t: Throwable) {
            Result.failure(Exception(friendlyError(t.message)))
        }
    }

    private fun friendlyError(msg: String?): String {
        val m = msg.orEmpty()
        if (m.isBlank()) return "解析失败"
        // 避免把超长 query 当错误刷屏
        if (m.length > 180 && (m.contains("utm_") || m.contains("activity_info"))) {
            return "抖音解析失败，请重试或换一条链接"
        }
        if (m.length > 220) return m.take(200) + "…"
        return m
    }

    private fun resolveViaYtdlp(url: String): ResolveResult? {
        val api = "${ytdlpBaseUrl()}/app/ytdlp/resolve"
        val body = JSONObject().put("url", url).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(api)
            .post(body)
            .header("User-Agent", "AlucardFilm/1.0")
            .build()
        resolveClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "ytdlp http ${resp.code}: $text")
                return null
            }
            val jo = JSONObject(text)
            if (!jo.optBoolean("ok", false)) {
                Log.w(TAG, "ytdlp fail: ${jo.optString("error")}")
                // 带错误信息抛出，便于 UI 显示
                throw Exception(jo.optString("error", "yt-dlp 解析失败"))
            }
            val media = jo.optString("url")
            if (media.isBlank()) return null
            val extractor = jo.optString("extractor").takeIf { it.isNotBlank() }
            return ResolveResult(
                title = jo.optString("title", "video"),
                mediaUrl = media,
                ext = jo.optString("ext", "mp4"),
                extractor = extractor
            )
        }
    }

    private data class Probe(val finalUrl: String, val contentType: String?, val filename: String?)

    private fun probe(url: String): Probe? {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code !in 200..399) return null
                val ct = resp.header("Content-Type")
                val cd = resp.header("Content-Disposition")
                val name = cd?.let {
                    Regex("filename\\*?=(?:UTF-8''|\"?)([^\";]+)", RegexOption.IGNORE_CASE)
                        .find(it)?.groupValues?.get(1)
                        ?.let { n -> URLDecoder.decode(n.trim('"'), "UTF-8") }
                }
                Probe(resp.request.url.toString(), ct, name)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractFromHtml(pageUrl: String): ResolveResult? {
        val req = Request.Builder()
            .url(pageUrl)
            .get()
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            val patterns = listOf(
                Pattern.compile("""<video[^>]+src=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""<source[^>]+src=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE),
                Pattern.compile(""""playAddr"\s*:\s*"([^"]+)""""),
                Pattern.compile(""""play_url"\s*:\s*"([^"]+)""""),
                Pattern.compile(""""videoUrl"\s*:\s*"([^"]+)""""),
                Pattern.compile("""(https?://[^"'\s]+\.mp4[^"'\s]*)""", Pattern.CASE_INSENSITIVE)
            )
            for (p in patterns) {
                val m = p.matcher(body)
                if (m.find()) {
                    var u = m.group(1) ?: continue
                    u = u.replace("\\u002F", "/").replace("\\/", "/")
                    if (u.startsWith("//")) u = "https:$u"
                    if (!u.startsWith("http")) continue
                    return ResolveResult(
                        title = guessNameFromUrl(u),
                        mediaUrl = u,
                        ext = extFromUrl(u)
                    )
                }
            }
        }
        return null
    }

    /**
     * 下载媒体到「我的下载」。
     * 抖音 CDN 需要正确 Referer；失败则回退服务器代下再拉到手机。
     * @param onProgress (done, total, progress 0..1)
     * @param sourceUrl 原始分享链接（用于服务端回退）
     */
    suspend fun download(
        context: Context,
        recordId: String,
        mediaUrl: String,
        title: String,
        ext: String,
        sourceUrl: String = "",
        onProgress: (Long, Long, Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        // 1) 直连 CDN
        val direct = downloadToFile(context, recordId, mediaUrl, title, ext, onProgress)
        if (direct.isSuccess) return@withContext direct

        val firstErr = direct.exceptionOrNull()?.message ?: "直连失败"
        Log.w(TAG, "direct download failed: $firstErr, try server")

        // 2) 服务端代下（服务器能访问抖音 CDN 时更稳），再拉回手机
        if (sourceUrl.isNotBlank()) {
            onProgress(0, 0, 0f)
            LinkDownloadStore.update(id = recordId, state = "DOWNLOADING", error = null)
            val server = downloadViaServer(sourceUrl)
            if (server.isSuccess) {
                val serverFileUrl = server.getOrNull().orEmpty()
                val abs = if (serverFileUrl.startsWith("http")) serverFileUrl
                else "${ytdlpBaseUrl()}$serverFileUrl"
                val r2 = downloadToFile(
                    context, recordId, abs, title, ext, onProgress,
                    refererOverride = ytdlpBaseUrl()
                )
                if (r2.isSuccess) return@withContext r2
                val msg = "服务端代下后拉取失败: ${r2.exceptionOrNull()?.message}"
                LinkDownloadStore.update(id = recordId, state = "ERROR", error = msg)
                return@withContext Result.failure(Exception(msg))
            } else {
                val msg = "下载失败: $firstErr；服务端代下: ${server.exceptionOrNull()?.message}"
                LinkDownloadStore.update(id = recordId, state = "ERROR", error = msg)
                return@withContext Result.failure(Exception(msg))
            }
        }

        LinkDownloadStore.update(id = recordId, state = "ERROR", error = firstErr)
        Result.failure(Exception(firstErr))
    }

    private fun downloadViaServer(sourceUrl: String): Result<String> {
        return try {
            val api = "${ytdlpBaseUrl()}/app/ytdlp/download"
            val body = JSONObject()
                .put("url", sourceUrl)
                .put("mode", "server")
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(api)
                .post(body)
                .header("User-Agent", "AlucardFilm/1.0")
                .build()
            // 服务端下完整视频可能较慢
            val slow = resolveClient.newBuilder()
                .readTimeout(600, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            slow.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Result.failure(Exception("HTTP ${resp.code}"))
                }
                val jo = JSONObject(text)
                if (!jo.optBoolean("ok", false)) {
                    return Result.failure(Exception(jo.optString("error", "server download failed")))
                }
                val fileUrl = jo.optString("file_url")
                if (fileUrl.isBlank()) {
                    return Result.failure(Exception("服务端未返回文件地址"))
                }
                Result.success(fileUrl)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun downloadToFile(
        context: Context,
        recordId: String,
        mediaUrl: String,
        title: String,
        ext: String,
        onProgress: (Long, Long, Float) -> Unit,
        refererOverride: String? = null
    ): Result<File> {
        return try {
            val dir = TorrentPaths.myDownloadsDir(context)
            val safeTitle = title
                .replace(Regex("[\\\\/:*?\"<>|#\\n\\r\\t]+"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(40)
                .ifBlank { "video" }
            val e = ext.removePrefix(".").ifBlank { "mp4" }
            var out = File(dir, "$safeTitle.$e")
            if (out.exists()) {
                out = File(dir, "${safeTitle}_${System.currentTimeMillis()}.$e")
            }
            val tmp = File(dir, "${out.name}.part")
            try {
                if (tmp.exists()) tmp.delete()
            } catch (_: Throwable) {
            }

            val referer = refererOverride
                ?: when {
                    mediaUrl.contains("douyin", ignoreCase = true) ||
                        mediaUrl.contains("byte", ignoreCase = true) ||
                        mediaUrl.contains("snssdk", ignoreCase = true) ||
                        mediaUrl.contains("aweme", ignoreCase = true) ||
                        mediaUrl.contains("douyinvod", ignoreCase = true) ->
                        "https://www.douyin.com/"
                    else -> mediaUrl.substringBefore("?").substringBeforeLast('/') + "/"
                }

            val req = Request.Builder()
                .url(mediaUrl)
                .get()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
                )
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", referer)
                .header("Origin", "https://www.douyin.com")
                .build()

            Log.i(TAG, "download start url=${mediaUrl.take(120)} referer=$referer -> ${out.name}")

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(Exception("HTTP ${resp.code}"))
                }
                val body = resp.body ?: return Result.failure(Exception("空响应"))
                val total = body.contentLength()
                var done = 0L
                var lastUi = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            val totalShow = if (total > 0) total else done
                            val prog = if (total > 0) {
                                (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            onProgress(done, totalShow, prog)
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 300 || (total > 0 && done >= total)) {
                                lastUi = now
                                LinkDownloadStore.update(
                                    id = recordId,
                                    doneBytes = done,
                                    totalBytes = totalShow,
                                    progress = prog,
                                    state = "DOWNLOADING",
                                    error = null
                                )
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (!tmp.exists() || tmp.length() < 1024) {
                    try {
                        tmp.delete()
                    } catch (_: Throwable) {
                    }
                    return Result.failure(Exception("下载文件过小或为空 (${tmp.length()} B)"))
                }
                if (out.exists()) out.delete()
                val renamed = tmp.renameTo(out)
                if (!renamed) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
            }
            if (!out.exists() || out.length() < 1024) {
                return Result.failure(Exception("保存失败"))
            }
            LinkDownloadStore.update(
                id = recordId,
                doneBytes = out.length(),
                totalBytes = out.length(),
                progress = 1f,
                state = "FINISHED",
                filePath = out.absolutePath,
                isFinished = true,
                error = null
            )
            Log.i(TAG, "download ok ${out.absolutePath} size=${out.length()}")
            Result.success(out)
        } catch (t: Throwable) {
            Log.e(TAG, "downloadToFile failed", t)
            Result.failure(t)
        }
    }

    private fun isVideoContentType(ct: String?): Boolean {
        if (ct.isNullOrBlank()) return false
        val c = ct.lowercase()
        return c.startsWith("video/") ||
            c.contains("mpegurl") ||
            c.contains("application/octet-stream") ||
            c.contains("mp4")
    }

    private fun extFromContentType(ct: String?): String? {
        if (ct == null) return null
        val c = ct.lowercase()
        return when {
            c.contains("mp4") -> "mp4"
            c.contains("webm") -> "webm"
            c.contains("mpegurl") || c.contains("m3u8") -> "m3u8"
            c.contains("quicktime") -> "mov"
            c.contains("mp3") -> "mp3"
            else -> null
        }
    }

    private fun extFromUrl(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".webm") -> "webm"
            path.endsWith(".mkv") -> "mkv"
            path.endsWith(".mov") -> "mov"
            path.endsWith(".m3u8") -> "m3u8"
            path.endsWith(".mp3") -> "mp3"
            path.endsWith(".m4a") -> "m4a"
            else -> "mp4"
        }
    }

    private fun guessNameFromUrl(url: String): String {
        val path = url.substringBefore('?')
        val last = path.substringAfterLast('/')
        return last.substringBeforeLast('.').ifBlank { "video" }
            .take(60)
    }
}
