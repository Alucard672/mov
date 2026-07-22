package com.gofilm.app.data.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.StateUpdateAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 内置种子引擎（libtorrent4j）。
 *
 * 重要：所有原生调用都在单线程执行；进度只从 alert 更新，
 * 避免在协程里对 TorrentHandle 调 isValid/status 导致 SIGSEGV。
 */
object TorrentEngine {
    private const val TAG = "TorrentEngine"

    /** 公共 Tracker，补充种子内置列表，显著提高找源速度 */
    private val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://opentracker.io:6969/announce",
        "udp://tracker1.bt.moack.co.kr:80/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://p4p.arenabg.com:1337/announce",
        "udp://tracker.internetwarriors.net:1337/announce",
        "udp://tracker.ololosh.space:6969/announce",
        "udp://tracker.birkenwald.de:6969/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "udp://open.dstud.io:6969/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "udp://bt1.archive.org:6969/announce",
        "udp://bt2.archive.org:6969/announce",
        "udp://movies.zsw.ca:6969/announce",
        "udp://tracker.auctor.tv:6969/announce",
        "udp://tracker.tryhackx.org:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://uploads.gamecoast.net:6969/announce"
    )

    /** 无同伴时周期性 reannounce */
    @Volatile
    private var lastBoostMs: Long = 0L

    /** 已连上同伴但 0 速度的起始时间 */
    @Volatile
    private var stallSinceMs: Long = 0L

    /** 边下边播：默认开顺序 + 片头/片尾 piece 优先 */
    @Volatile
    private var sequentialEnabled: Boolean = true

    /** 选中文件片头连续已拥有的 piece 数（原生线程更新） */
    @Volatile
    private var headHavePieces: Int = 0

    @Volatile
    private var headNeedPieces: Int = 16

    /** 片尾连续已拥有（MP4 moov 常在尾部，边下边播关键） */
    @Volatile
    private var tailHavePieces: Int = 0

    @Volatile
    private var tailNeedPieces: Int = 8

    /** 目标片头/片尾数据量（按 piece 大小换算 need 数） */
    private const val HEAD_TARGET_BYTES = 4L * 1024 * 1024
    private const val TAIL_TARGET_BYTES = 2L * 1024 * 1024

    private val nativeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "torrent-native").apply { isDaemon = true }
    }

    @Volatile
    private var session: SessionManager? = null

    @Volatile
    private var infoHash: Sha1Hash? = null

    @Volatile
    private var currentInfo: TorrentInfo? = null

    @Volatile
    private var saveDir: File? = null

    @Volatile
    private var selectedFileIndex: Int = 0

    /** 当前下载任务 id（对应 TorrentDownloadStore） */
    @Volatile
    var currentJobId: String? = null
        private set

    @Volatile
    private var currentFilePath: String? = null

    private val progressRef = AtomicReference(Progress.empty())

    private val started = AtomicBoolean(false)
    private var alertListener: AlertListener? = null

    data class Progress(
        val name: String,
        val state: String,
        val progress: Float,
        val downloadRate: Int,
        val numPeers: Int,
        val numSeeds: Int,
        val fileDoneBytes: Long,
        val fileTotalBytes: Long,
        val totalWantedDone: Long,
        val totalWanted: Long,
        val isFinished: Boolean,
        val error: String?,
        /** tracker/DHT 侧已知同伴（不一定已连接） */
        val listPeers: Int = 0,
        val listSeeds: Int = 0,
        val connectCandidates: Int = 0,
        val dhtNodes: Long = 0,
        val currentTracker: String = "",
        val announcingToTrackers: Boolean = false,
        val announcingToDht: Boolean = false,
        val networkHint: String? = null,
        /** 选中文件从片头起连续已下载的 piece 数 */
        val headHave: Int = 0,
        /** 开播需要的连续片头 piece 数 */
        val headNeed: Int = 16,
        /** 片尾连续已下载 piece 数（MP4 索引常在尾部） */
        val tailHave: Int = 0,
        val tailNeed: Int = 8
    ) {
        val fileProgress: Float
            get() = if (fileTotalBytes <= 0L) 0f
            else (fileDoneBytes.toDouble() / fileTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)

        /** 片头连续分片是否够开播 */
        val headReady: Boolean get() = headHave >= headNeed || isFinished

        /** 片尾（moov）是否够开播 */
        val tailReady: Boolean get() = tailHave >= tailNeed || isFinished || tailNeed <= 0

        companion object {
            fun empty() = Progress(
                name = "",
                state = "IDLE",
                progress = 0f,
                downloadRate = 0,
                numPeers = 0,
                numSeeds = 0,
                fileDoneBytes = 0,
                fileTotalBytes = 0,
                totalWantedDone = 0,
                totalWanted = 0,
                isFinished = false,
                error = null
            )
        }
    }

    /** 线程安全读取最新进度（无原生调用） */
    fun snapshot(): Progress = progressRef.get()

    fun hasActiveDownload(): Boolean {
        val p = progressRef.get()
        return currentJobId != null &&
            p.state != "IDLE" &&
            p.state != "STOPPED" &&
            !p.isFinished &&
            p.error == null
    }

    fun isIdle(): Boolean {
        val p = progressRef.get()
        return currentJobId == null || p.state == "IDLE" || p.state == "STOPPED"
    }

    fun currentFile(): File? {
        currentFilePath?.let { path ->
            val f = File(path)
            if (f.exists() || f.parentFile?.exists() == true) return f
        }
        val ti = currentInfo ?: return null
        val dir = saveDir ?: return null
        return resolveFile(dir, ti, selectedFileIndex)
    }

    /** 是否像把索引写在文件尾的容器（MP4 系最常见） */
    private fun needsTailIndex(fileName: String?): Boolean {
        val n = fileName?.lowercase().orEmpty()
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".m4v") ||
            n.endsWith(".m4a") || n.endsWith(".3gp") || n.endsWith(".f4v")
    }

    /**
     * 是否够开播。
     *
     * 注意：libtorrent 预分配时 `File.length()` 一开始就是整文件大小，
     * **不能**用 length 判断；必须用真实已下字节 + **片头/片尾连续 piece**。
     * 多数 MP4 的 moov 在尾部：只顺序下片头会一直播不了，必须同时下片尾。
     */
    fun isPlayable(minBytes: Long = 2L * 1024 * 1024): Boolean {
        val p = snapshot()
        if (p.error != null && p.state == "ERROR") return false
        if (p.isFinished) return true
        val file = currentFile() ?: return false
        if (!file.exists()) return false
        val done = p.fileDoneBytes.coerceAtLeast(0L)
        if (done < minBytes / 2 && !p.headReady) return false

        val needTail = needsTailIndex(file.name)
        // 有 head/tail 统计时严格判断
        if (p.headNeed > 0) {
            if (!p.headReady) return false
            if (needTail && p.tailNeed > 0 && !p.tailReady) return false
            return done >= (minBytes / 2).coerceAtLeast(512L * 1024)
        }
        return done >= minBytes
    }

    /** UI 在播放失败时调用：强制再抢片头 + 片尾（moov） */
    fun requestHeadBoost() {
        requestPlayBoost()
    }

    /**
     * 边下边播失败时调用：重申片头/片尾最高优先级。
     * MP4 索引在尾时只抢片头没用，必须同步抢尾。
     */
    fun requestPlayBoost() {
        val done = CountDownLatch(1)
        nativeExecutor.execute {
            try {
                val sm = session
                val hash = infoHash
                val ti = currentInfo
                if (sm != null && hash != null && ti != null) {
                    val h = sm.find(hash) ?: return@execute
                    applyStreamingPriorities(h, ti, selectedFileIndex, forceTail = true)
                    try {
                        h.forceReannounce()
                    } catch (_: Throwable) {
                    }
                    refreshHeadTailHave(h, ti, selectedFileIndex)
                    Log.i(
                        TAG,
                        "requestPlayBoost file=$selectedFileIndex " +
                            "head=$headHavePieces/$headNeedPieces tail=$tailHavePieces/$tailNeedPieces " +
                            "seq=$sequentialEnabled"
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "requestPlayBoost: ${t.message}")
            } finally {
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
    }

    /** 真实已下载字节（优先进度快照，不信 file.length） */
    fun downloadedBytes(): Long = snapshot().fileDoneBytes.coerceAtLeast(0L)

    /** 选中文件总大小（元数据） */
    fun selectedFileTotalBytes(): Long {
        val p = snapshot()
        if (p.fileTotalBytes > 0) return p.fileTotalBytes
        return try {
            currentInfo?.files()?.fileSize(selectedFileIndex) ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * 边下边播：确保选中文件 [offsetInFile, offsetInFile+length) 对应 piece 已齐。
     * 未齐则提高优先级并等待。供 ExoPlayer DataSource 在读文件前调用。
     *
     * @return true=范围内 piece 都有；false=超时仍缺
     */
    fun waitForSelectedFileRange(
        offsetInFile: Long,
        length: Long,
        timeoutMs: Long = 20_000L
    ): Boolean {
        if (length <= 0L) return true
        val p0 = snapshot()
        if (p0.isFinished) return true

        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(500L)
        while (System.currentTimeMillis() < deadline) {
            val result = AtomicReference<Boolean?>(null)
            val latch = CountDownLatch(1)
            nativeExecutor.execute {
                try {
                    val sm = session
                    val hash = infoHash
                    val ti = currentInfo
                    if (sm == null || hash == null || ti == null) {
                        result.set(false)
                        return@execute
                    }
                    val h = sm.find(hash)
                    if (h == null) {
                        result.set(false)
                        return@execute
                    }
                    val ok = ensureFileRangeOnNative(
                        h, ti, selectedFileIndex, offsetInFile, length
                    )
                    result.set(ok)
                } catch (t: Throwable) {
                    Log.w(TAG, "waitForRange: ${t.message}")
                    result.set(false)
                } finally {
                    latch.countDown()
                }
            }
            try {
                latch.await(3, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                return false
            }
            if (result.get() == true) return true
            try {
                Thread.sleep(180)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    /** 原生线程：提高 [offset, offset+len) 对应 piece 优先级，并检查是否已齐 */
    private fun ensureFileRangeOnNative(
        h: TorrentHandle,
        ti: TorrentInfo,
        fileIndex: Int,
        offsetInFile: Long,
        length: Long
    ): Boolean {
        val fs = ti.files()
        val fileSize = fs.fileSize(fileIndex)
        if (fileSize <= 0) return false
        val start = offsetInFile.coerceIn(0L, fileSize)
        val end = (offsetInFile + length).coerceIn(start + 1, fileSize)
        val pieceLen = ti.pieceLength().coerceAtLeast(1)
        val numPieces = ti.numPieces()
        val absStart = fs.fileOffset(fileIndex) + start
        val absEnd = fs.fileOffset(fileIndex) + end
        val first = (absStart / pieceLen).toInt().coerceIn(0, numPieces - 1)
        val last = ((absEnd - 1) / pieceLen).toInt().coerceIn(0, numPieces - 1)
        var all = true
        for (i in first..last) {
            val have = try {
                h.havePiece(i)
            } catch (_: Throwable) {
                false
            }
            if (!have) {
                all = false
                try {
                    h.piecePriority(i, Priority.TOP_PRIORITY)
                    h.setPieceDeadline(i, (i - first) * 20)
                } catch (_: Throwable) {
                }
            }
        }
        if (!all) {
            // 缺片时关掉顺序，避免只拉中段
            try {
                h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                sequentialEnabled = false
            } catch (_: Throwable) {
            }
            try {
                h.resume()
            } catch (_: Throwable) {
            }
        }
        return all
    }

    /** 磁力解析进度文案（UI 可直接展示） */
    private val _magnetStatus = MutableStateFlow("")
    val magnetStatus: StateFlow<String> = _magnetStatus.asStateFlow()

    private fun setMagnetStatus(msg: String) {
        _magnetStatus.value = msg
        Log.i(TAG, "magnet: $msg")
        progressRef.updateAndGet {
            it.copy(state = "DOWNLOADING_METADATA", name = msg, error = null, isFinished = false)
        }
    }

    /**
     * 解析磁力链接元数据，返回 .torrent 字节。
     *
     * 策略（按速度优先）：
     * 1) 自家服务器代理（通常 1–3 秒，有缓存则毫秒级）
     * 2) 公网 HTTP 缓存（短超时）
     * 3) DHT 兜底（最多约 25 秒）
     */
    fun fetchMagnetMetadata(
        context: Context,
        magnetUri: String,
        timeoutSec: Int = 25
    ): ByteArray {
        TorrentDownloadStore.init(context)
        appContext = context.applicationContext
        val uri = normalizeMagnet(magnetUri)
        require(uri.startsWith("magnet:", ignoreCase = true)) {
            "请输入有效的磁力链接（magnet:?xt=urn:btih:…）"
        }
        val hash = extractInfoHash(uri)
            ?: error("无法识别 info-hash，请检查磁力链接")

        val t0 = System.currentTimeMillis()
        fun elapsed() = (System.currentTimeMillis() - t0) / 1000

        setMagnetStatus("识别成功 $hash · 正在拉元数据…")
        Log.i(TAG, "fetchMagnet start hash=$hash")

        // 并行：服务器/镜像拿 .torrent（与本地选种子文件等价的数据源）
        // 注意：下载阶段必须用这份完整 .torrent，和「种子」TAB 同一路径。
        try {
            setMagnetStatus("获取种子元数据… ${elapsed()}s")
            val h = hash.lowercase()
            val Hu = hash.uppercase()
            val urls = listOf(
                magnetProxyUrl(hash),
                "https://itorrents.org/torrent/$h.torrent",
                "http://itorrents.org/torrent/$h.torrent",
                "https://itorrents.org/torrent/$Hu.torrent"
            )
            val httpBytes = fetchTorrentHttp(urls = urls, connectMs = 5_000, readMs = 15_000)
            if (httpBytes != null) {
                // 用原生 TorrentInfo 校验，并核对 info-hash
                val ti = TorrentInfo(httpBytes)
                val got = try {
                    ti.infoHash().toHex().lowercase()
                } catch (_: Throwable) {
                    ""
                }
                if (got.isNotBlank() && got != h.lowercase() && got != hash.lowercase()) {
                    Log.w(TAG, "HTTP torrent hash mismatch expect=$hash got=$got, still try DHT")
                } else {
                    softValidateTorrent(httpBytes)
                    setMagnetStatus("解析完成（${elapsed()}s · ${httpBytes.size / 1024}KB · 与本地种子相同格式）")
                    Log.i(TAG, "magnet metadata via HTTP ok hash=$got files=${ti.numFiles()}")
                    return httpBytes
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "HTTP magnet: ${t.message}")
        }

        // DHT 原生 fetchMagnet（得到的也是标准 .torrent 字节）
        setMagnetStatus("DHT 解析中… ${elapsed()}s（最多约 ${timeoutSec}s）")
        val dhtTimeout = timeoutSec.coerceIn(20, 60)
        val result = AtomicReference<Any?>(null)
        val done = CountDownLatch(1)
        nativeExecutor.execute {
            try {
                // 无 VPN 时不要 bind 歪网卡
                if (TorrentNetwork.isVpnActive(context)) {
                    TorrentNetwork.bindDirectNetwork(context, waitMs = 1500L)
                } else {
                    TorrentNetwork.unbind(context)
                }
                result.set(fetchMagnetOnNative(context, uri, dhtTimeout))
            } catch (t: Throwable) {
                Log.e(TAG, "fetchMagnet DHT failed", t)
                result.set(t)
            } finally {
                done.countDown()
            }
        }
        val deadline = System.currentTimeMillis() + (dhtTimeout + 8) * 1000L
        while (!done.await(1, TimeUnit.SECONDS)) {
            if (System.currentTimeMillis() > deadline) break
            setMagnetStatus("DHT 解析中… ${elapsed()}s")
        }
        if (result.get() == null && !done.await(2, TimeUnit.SECONDS)) {
            error("解析超时（${elapsed()}s）。请检查网络后重试")
        }
        when (val r = result.get()) {
            is ByteArray -> {
                if (r.isEmpty()) error("磁力解析结果为空")
                // 校验
                try {
                    TorrentInfo(r)
                } catch (t: Throwable) {
                    throw RuntimeException("DHT 元数据无效: ${t.message}", t)
                }
                setMagnetStatus("解析完成（DHT ${elapsed()}s）")
                return r
            }
            is Throwable -> {
                val msg = r.message ?: "磁力解析失败"
                throw RuntimeException(msg, r)
            }
            else -> error("磁力解析失败（${elapsed()}s）")
        }
    }

    /** 磁力代理：短路径 /t/{hash}.torrent（Nginx 反代） */
    private fun magnetProxyUrl(infoHashHex: String): String {
        return com.gofilm.app.data.ServerConfig.magnetProxyUrl(infoHashHex)
    }

    /**
     * 短超时 HTTP 拉取；仅做 bencode 头检查，不调用原生 TorrentInfo（避免阻塞）。
     */
    private fun fetchTorrentHttp(
        urls: List<String>,
        connectMs: Int,
        readMs: Int
    ): ByteArray? {
        for (url in urls) {
            try {
                Log.i(TAG, "HTTP magnet try $url")
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = connectMs
                conn.readTimeout = readMs
                conn.requestMethod = "GET"
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AlucardFilm/1.0"
                )
                conn.setRequestProperty("Accept", "*/*")
                val code = try {
                    conn.responseCode
                } catch (t: Throwable) {
                    conn.disconnect()
                    Log.w(TAG, "HTTP connect fail: ${t.message}")
                    continue
                }
                if (code !in 200..299) {
                    conn.disconnect()
                    Log.w(TAG, "HTTP magnet $code for $url")
                    continue
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.size < 64) continue
                if (bytes[0] != 'd'.code.toByte()) {
                    Log.w(TAG, "HTTP not bencode from $url head=${bytes.take(20)}")
                    continue
                }
                // 轻量校验：能被纯 Java bencode 解析即可
                try {
                    TorrentBencode.parse(bytes)
                } catch (t: Throwable) {
                    Log.w(TAG, "HTTP bencode invalid: ${t.message}")
                    continue
                }
                return bytes
            } catch (t: Throwable) {
                Log.w(TAG, "HTTP magnet error ${t.message}")
            }
        }
        return null
    }

    private fun softValidateTorrent(bytes: ByteArray) {
        try {
            TorrentBencode.parse(bytes)
        } catch (t: Throwable) {
            throw RuntimeException("磁力元数据无效: ${t.message}", t)
        }
    }

    private fun fetchMagnetOnNative(
        context: Context,
        magnetUri: String,
        timeoutSec: Int
    ): ByteArray {
        val sm = ensureStartedOnNative()
        // 短预热，避免白等 20 秒
        warmUpDht(sm, maxWaitMs = 4_000L)
        val tempDir = File(context.cacheDir, "magnet_meta").apply { mkdirs() }
        setMagnetStatus("DHT 获取元数据…")
        Log.i(TAG, "fetchMagnet DHT timeout=${timeoutSec}s dhtNodes=${try { sm.dhtNodes() } catch (_: Throwable) { -1 }}")
        val bytes = try {
            sm.fetchMagnet(magnetUri, timeoutSec, tempDir)
        } catch (t: Throwable) {
            throw RuntimeException("DHT 磁力解析异常: ${t.message}", t)
        }
        if (bytes == null || bytes.isEmpty()) {
            error("DHT 未能获取元数据（无同伴）。可稍后重试")
        }
        softValidateTorrent(bytes)
        return bytes
    }

    private fun warmUpDht(sm: SessionManager, maxWaitMs: Long) {
        val start = System.currentTimeMillis()
        try {
            if (!sm.isDhtRunning) sm.startDht()
        } catch (_: Throwable) {
        }
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val n = try {
                sm.dhtNodes()
            } catch (_: Throwable) {
                0L
            }
            if (n >= 10L) {
                Log.i(TAG, "DHT warm ok nodes=$n")
                return
            }
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                return
            }
        }
        Log.w(TAG, "DHT warm partial nodes=${try { sm.dhtNodes() } catch (_: Throwable) { -1 }}")
    }

    /** 从磁力或纯 hash 文本提取 info-hash（hex40 或 base32 32） */
    fun extractInfoHash(raw: String): String? {
        val s = raw.trim()
        // btih: 后跟 40 hex 或 32 base32
        Regex("""btih:([0-9a-fA-F]{40})""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
            ?.lowercase()?.let { return it }
        Regex("""btih:([A-Z2-7]{32})""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
            ?.uppercase()?.let { return it }
        val compact = s.replace(Regex("""(?i)urn:btih:"""), "")
            .replace(Regex("""[^0-9A-Fa-z]"""), "")
        if (compact.length == 40 && compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return compact.lowercase()
        }
        if (compact.length == 32 && compact.all { it.isLetterOrDigit() }) {
            return compact.uppercase()
        }
        return null
    }

    /**
     * 规范化磁力：清理空白/不可见字符，dn 重新编码，强制附加公共 Tracker。
     * 原链接 dn 里常有空格、中括号，会导致 parse_magnet_uri 失败。
     */
    fun normalizeMagnet(raw: String): String {
        var s = raw.trim()
            .replace("\uFEFF", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace(Regex("\\s+"), " ")
        if (s.isBlank()) return s

        val hash = extractInfoHash(s) ?: run {
            if (s.startsWith("magnet:", ignoreCase = true)) return s
            return s
        }

        // 提取 dn（原始可能未编码且含空格）
        var displayName: String? = null
        Regex("""[?&]dn=([^&]*)""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.let { rawDn ->
            try {
                displayName = java.net.URLDecoder.decode(rawDn.replace("+", "%20"), Charsets.UTF_8.name())
            } catch (_: Throwable) {
                displayName = rawDn
            }
        }

        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            displayName?.takeIf { it.isNotBlank() }?.let { dn ->
                append("&dn=")
                append(java.net.URLEncoder.encode(dn, Charsets.UTF_8.name()))
            }
            // 始终附加公共 Tracker（即使原链已有，多带无妨）
            for (t in PUBLIC_TRACKERS) {
                append("&tr=")
                append(java.net.URLEncoder.encode(t, Charsets.UTF_8.name()))
            }
        }
    }

    /**
     * 在原生线程上启动下载。阻塞直到加入完成或超时。
     */
    fun startDownload(
        context: Context,
        torrentBytes: ByteArray,
        fileIndex: Int,
        timeoutSec: Long = 45
    ): File {
        val result = AtomicReference<Any?>(null)
        val done = CountDownLatch(1)
        nativeExecutor.execute {
            try {
                result.set(startDownloadOnNative(context, torrentBytes, fileIndex))
            } catch (t: Throwable) {
                Log.e(TAG, "startDownload failed", t)
                result.set(t)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(timeoutSec, TimeUnit.SECONDS)) {
            error("启动种子超时")
        }
        when (val r = result.get()) {
            is File -> return r
            is Throwable -> throw r
            else -> error("启动种子失败")
        }
    }

    private fun startDownloadOnNative(
        context: Context,
        torrentBytes: ByteArray,
        fileIndex: Int
    ): File {
        TorrentDownloadStore.init(context)
        appContext = context.applicationContext
        // 仅在真正有 VPN 时才绕路；无 VPN 时强制走系统默认路由。
        // 之前无论是否 VPN 都 bind/重建 session，反而会导致「种子正常、磁力异常」类问题。
        val vpn = TorrentNetwork.isVpnActive(context)
        val netHint = TorrentNetwork.proxyHint(context)
        if (vpn) {
            val bound = TorrentNetwork.bindDirectNetwork(context, waitMs = 2500L)
            Log.i(TAG, "VPN detected, bind direct=$bound")
            recreateSessionOnNative("vpn bypass")
        } else {
            TorrentNetwork.unbind(context)
            Log.i(TAG, "no VPN, use default network (same path as local .torrent)")
        }
        if (netHint != null) {
            Log.w(TAG, netHint)
        }
        val ti = TorrentInfo(torrentBytes)
        Log.i(
            TAG,
            "startDownload name=${ti.name()} files=${ti.numFiles()} " +
                "hash=${try { ti.infoHash().toHex() } catch (_: Throwable) { "?" }} " +
                "fileIndex=$fileIndex size=${try { ti.files().fileSize(fileIndex) } catch (_: Throwable) { -1 }}"
        )
        val hashHex = try {
            ti.infoHash().toHex().lowercase()
        } catch (_: Throwable) {
            ""
        }
        val n = ti.numFiles()
        require(fileIndex in 0 until n) { "文件索引无效: $fileIndex / $n" }

        // —— 已完成：直接复用，绝不重复下载 ——
        val finished = if (hashHex.isNotBlank()) {
            TorrentDownloadStore.findFinishedWithFile(hashHex, fileIndex)
        } else null
        if (finished != null) {
            val f = File(finished.filePath)
            currentJobId = finished.id
            currentFilePath = f.absolutePath
            currentInfo = ti
            selectedFileIndex = fileIndex
            saveDir = f.parentFile
            progressRef.set(
                Progress(
                    name = finished.torrentName.ifBlank { ti.name() },
                    state = "FINISHED",
                    progress = 1f,
                    downloadRate = 0,
                    numPeers = 0,
                    numSeeds = 0,
                    fileDoneBytes = finished.totalBytes.coerceAtLeast(f.length()),
                    fileTotalBytes = finished.totalBytes.coerceAtLeast(f.length()),
                    totalWantedDone = finished.totalBytes.coerceAtLeast(f.length()),
                    totalWanted = finished.totalBytes.coerceAtLeast(f.length()),
                    isFinished = true,
                    error = null,
                    networkHint = null
                )
            )
            TorrentDownloadStore.markFinished(finished.id, f.absolutePath)
            Log.i(TAG, "reuse finished download ${finished.id}")
            return f
        }

        // —— 同任务已在下：直接返回 ——
        val existing = if (hashHex.isNotBlank()) {
            TorrentDownloadStore.findByHashAndIndex(hashHex, fileIndex)
        } else null
        if (existing != null && existing.isActive && currentJobId == existing.id && hasActiveDownload()) {
            return File(existing.filePath)
        }

        // 切换到其他种子时，停掉当前任务（已完成不会被 markStopped 破坏）
        val oldJob = currentJobId
        if (oldJob != null && oldJob != existing?.id) {
            stopCurrentOnNative(markStoreStopped = true)
            TorrentDownloadStore.markStopped(oldJob)
        }

        val sm = ensureStartedOnNative()
        // 下到应用外部 Downloads/Alucard/{hash}，完成后可在「我的下载」看到
        val dir = TorrentPaths.workDir(context, hashHex)
        saveDir = dir

        currentInfo = ti
        selectedFileIndex = fileIndex

        val fileTotal = ti.files().fileSize(fileIndex)
        val outFile = resolveFile(dir, ti, fileIndex)
        currentFilePath = outFile.absolutePath

        val jobId = existing?.id ?: java.util.UUID.randomUUID().toString()
        val torrentPath = TorrentDownloadStore.saveTorrentBytes(context, jobId, torrentBytes)
        val rec = TorrentDownloadStore.create(
            id = jobId,
            torrentName = ti.name(),
            fileName = outFile.name,
            fileIndex = fileIndex,
            filePath = outFile.absolutePath,
            totalBytes = fileTotal,
            torrentFilePath = torrentPath,
            infoHashHex = hashHex
        )
        currentJobId = rec.id

        progressRef.set(
            Progress(
                name = ti.name(),
                state = "STARTING",
                progress = existing?.progress ?: 0f,
                downloadRate = 0,
                numPeers = 0,
                numSeeds = 0,
                fileDoneBytes = existing?.doneBytes ?: 0L,
                fileTotalBytes = fileTotal,
                totalWantedDone = existing?.doneBytes ?: 0L,
                totalWanted = fileTotal,
                isFinished = false,
                error = null,
                networkHint = netHint
            )
        )

        // 只下选中文件 + 顺序 + 片头/片尾优先（边下边播关键）
        val priorities = Array(n) { Priority.IGNORE }
        priorities[fileIndex] = Priority.TOP_PRIORITY
        sequentialEnabled = true
        stallSinceMs = 0L
        headHavePieces = 0
        tailHavePieces = 0
        headNeedPieces = piecesForBytes(ti, HEAD_TARGET_BYTES)
        tailNeedPieces = piecesForBytes(ti, TAIL_TARGET_BYTES)

        val latch = CountDownLatch(1)
        val addError = AtomicReference<String?>(null)
        val addedOk = AtomicBoolean(false)

        val oneShot = object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.ADD_TORRENT.swig())

            override fun alert(alert: Alert<*>) {
                if (alert !is AddTorrentAlert) return
                try {
                    val err = alert.error()
                    if (err != null && err.isError) {
                        addError.set(err.getMessage())
                    } else {
                        infoHash = ti.infoHash()
                        addedOk.set(true)
                    }
                } catch (t: Throwable) {
                    addError.set(t.message ?: "ADD_TORRENT 处理失败")
                } finally {
                    latch.countDown()
                }
            }
        }

        sm.addListener(oneShot)
        try {
            // 不加 SEQUENTIAL：开播前只拉片头+片尾，避免 3MB/s 全砸在中段仍无法播
            sm.download(
                ti,
                dir,
                null,
                priorities,
                emptyList(),
                TorrentFlags.UPDATE_SUBSCRIBE
            )
            if (!latch.await(30, TimeUnit.SECONDS)) {
                error("添加种子超时，请检查网络")
            }
            addError.get()?.let { error("添加种子失败: $it") }
            if (!addedOk.get()) error("未成功添加种子")

            try {
                val hash = infoHash ?: ti.infoHash()
                try {
                    val handle = sm.find(hash)
                    if (handle != null) {
                        handle.prioritizeFiles(priorities)
                        try {
                            handle.filePriority(fileIndex, Priority.TOP_PRIORITY)
                        } catch (_: Throwable) {
                        }
                        // 片头 + 片尾（moov）最高优先；齐了才开顺序
                        applyStreamingPriorities(handle, ti, fileIndex, forceTail = true)
                    } else {
                        Log.w(TAG, "find handle null after add, hash=${hash.toHex()}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "prioritizeFiles: ${t.message}")
                }
                stabilizeAndBoost(sm, hash, force = true)
            } catch (t: Throwable) {
                Log.w(TAG, "boost peers: ${t.message}")
            }

            progressRef.updateAndGet { it.copy(state = "DOWNLOADING") }
            publishToStore()
            Log.i(TAG, "download started -> ${outFile.absolutePath}")
            return outFile
        } finally {
            try {
                sm.removeListener(oneShot)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 磁力专用：有 .torrent 字节时与本地种子同一路径；
     * 若同时提供 magnet URI，会把公共 Tracker 合并进会话（stabilize 里已加）。
     */
    fun startMagnetDownload(
        context: Context,
        torrentBytes: ByteArray,
        fileIndex: Int,
        magnetUri: String? = null,
        timeoutSec: Long = 45
    ): File {
        // 与 startDownload 相同，保证磁力解析出的种子和本地 .torrent 走同一引擎路径
        Log.i(TAG, "startMagnetDownload magnet=${magnetUri?.take(80)} bytes=${torrentBytes.size}")
        return startDownload(context, torrentBytes, fileIndex, timeoutSec)
    }

    /**
     * 从未完成记录继续下载：读取本地保存的 .torrent，走同一 startDownload 路径。
     * 工作目录不变，libtorrent 会校验已有分片并断点续传。
     */
    fun resumeDownload(
        context: Context,
        record: TorrentDownloadRecord,
        timeoutSec: Long = 45
    ): File {
        require(!record.isFinished) { "该任务已完成，无需继续下载" }
        if (record.isActive && currentJobId == record.id && hasActiveDownload()) {
            Log.i(TAG, "resumeDownload already active id=${record.id}")
            return File(record.filePath)
        }
        val path = record.torrentFilePath
        require(path.isNotBlank()) { "缺少种子元数据，无法继续下载，请重新选择种子" }
        val meta = File(path)
        require(meta.exists() && meta.length() > 0L) {
            "种子文件已丢失，无法继续下载，请重新选择种子"
        }
        val bytes = meta.readBytes()
        Log.i(
            TAG,
            "resumeDownload id=${record.id} fileIndex=${record.fileIndex} " +
                "done=${record.doneBytes}/${record.totalBytes} meta=$path"
        )
        return startDownload(context, bytes, record.fileIndex, timeoutSec)
    }

    @Volatile
    private var appContext: Context? = null

    /** 完成后复制一份到「我的下载」根目录并更新记录路径 */
    private fun onDownloadFinishedPromote() {
        val id = currentJobId ?: return
        val srcPath = currentFilePath ?: return
        val ctx = appContext ?: return
        val src = File(srcPath)
        if (!src.exists()) {
            TorrentDownloadStore.markFinished(id, srcPath)
            return
        }
        val promoted = try {
            TorrentPaths.promoteFinishedCopy(ctx, src, src.name)
        } catch (_: Throwable) {
            src
        }
        currentFilePath = promoted.absolutePath
        TorrentDownloadStore.markFinished(id, promoted.absolutePath)
        Log.i(TAG, "finished promoted to ${promoted.absolutePath}")
    }

    private fun publishToStore() {
        val id = currentJobId ?: return
        val p = progressRef.get()
        TorrentDownloadStore.updateProgress(
            id = id,
            doneBytes = p.fileDoneBytes,
            totalBytes = p.fileTotalBytes,
            progress = p.fileProgress,
            state = p.state,
            downloadRate = p.downloadRate,
            numPeers = p.numPeers,
            isFinished = p.isFinished,
            error = p.error
        )
    }

    /**
     * 稳定下载 + 补充 Tracker + DHT 取 peer 强制 connect：
     * 仅在原生线程调用。
     */
    private fun stabilizeAndBoost(sm: SessionManager, hash: Sha1Hash, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastBoostMs < 10_000L) return
        lastBoostMs = now
        val h = try {
            sm.find(hash)
        } catch (t: Throwable) {
            Log.w(TAG, "find for boost: ${t.message}")
            null
        } ?: return
        try {
            try {
                h.unsetFlags(TorrentFlags.AUTO_MANAGED)
            } catch (_: Throwable) {
            }
            try {
                h.unsetFlags(TorrentFlags.PAUSED)
            } catch (_: Throwable) {
            }
            try {
                h.unsetFlags(TorrentFlags.UPLOAD_MODE)
            } catch (_: Throwable) {
            }
            try {
                h.unsetFlags(TorrentFlags.STOP_WHEN_READY)
            } catch (_: Throwable) {
            }
            val pNow = progressRef.get()
            try {
                h.resume()
            } catch (_: Throwable) {
            }
            try {
                h.swig().set_max_connections(200)
            } catch (_: Throwable) {
            }
            // 只关心选中文件 + 片头 piece
            try {
                val ti = currentInfo
                if (ti != null) {
                    val n = ti.numFiles()
                    if (n > 0 && selectedFileIndex in 0 until n) {
                        val pri = Array(n) { Priority.IGNORE }
                        pri[selectedFileIndex] = Priority.TOP_PRIORITY
                        h.prioritizeFiles(pri)
                    }
                    applyStreamingPriorities(h, ti, selectedFileIndex, forceTail = true)
                    // 有同伴完全 0 速度超过阈值：额外要一点中段，但仍保持片头/尾优先
                    if (pNow.numPeers > 0 && pNow.downloadRate <= 0 && pNow.fileDoneBytes <= 0L &&
                        stallSinceMs > 0L && System.currentTimeMillis() - stallSinceMs >= 12_000L
                    ) {
                        boostSelectedFilePieces(h, ti, selectedFileIndex)
                    }
                    refreshHeadTailHave(h, ti, selectedFileIndex)
                }
            } catch (_: Throwable) {
            }
            for (url in PUBLIC_TRACKERS) {
                try {
                    h.addTracker(AnnounceEntry(url))
                } catch (_: Throwable) {
                }
            }
            try {
                h.forceReannounce()
            } catch (_: Throwable) {
            }
            try {
                h.forceDHTAnnounce()
            } catch (_: Throwable) {
            }
            try {
                h.setDownloadLimit(0)
                h.setUploadLimit(0)
            } catch (_: Throwable) {
            }
            // 候选有但已连接=0 时强制 connect（DHT 取 peer 有超时，仅在 force 时做）
            if (force) {
                forceConnectDhtPeers(sm, h, hash)
            }
            forceConnectListedPeers(h)
            Log.i(
                TAG,
                "torrent stabilized trackers=${PUBLIC_TRACKERS.size} force=$force " +
                    "seq=$sequentialEnabled peers=${pNow.numPeers} rate=${pNow.downloadRate} done=${pNow.fileDoneBytes}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "stabilizeAndBoost: ${t.message}")
        }
    }

    private fun piecesForBytes(ti: TorrentInfo, targetBytes: Long): Int {
        val pieceLen = try {
            ti.pieceLength().coerceAtLeast(1)
        } catch (_: Throwable) {
            256 * 1024
        }
        val n = ((targetBytes + pieceLen - 1) / pieceLen).toInt().coerceAtLeast(4)
        return n.coerceIn(4, 64)
    }

    private data class FilePieceRange(
        val first: Int,
        val last: Int,
        val headEnd: Int,
        val tailStart: Int
    )

    private fun filePieceRange(ti: TorrentInfo, fileIndex: Int): FilePieceRange? {
        return try {
            val fs = ti.files()
            val fileSize = fs.fileSize(fileIndex)
            if (fileSize <= 0) return null
            val numPieces = ti.numPieces().coerceAtLeast(1)
            // 优先用 libtorrent 精确映射，避免 offset 计算偏差
            val first = try {
                fs.pieceIndexAtFile(fileIndex)
            } catch (_: Throwable) {
                val pieceLen = ti.pieceLength().coerceAtLeast(1)
                (fs.fileOffset(fileIndex) / pieceLen).toInt()
            }.coerceIn(0, numPieces - 1)
            val last = try {
                fs.lastPieceIndexAtFile(fileIndex)
            } catch (_: Throwable) {
                val pieceLen = ti.pieceLength().coerceAtLeast(1)
                val endOff = fs.fileOffset(fileIndex) + fileSize
                ((endOff - 1) / pieceLen).toInt()
            }.coerceIn(0, numPieces - 1)
            if (last < first) return null
            val headNeed = piecesForBytes(ti, HEAD_TARGET_BYTES)
            val tailNeed = piecesForBytes(ti, TAIL_TARGET_BYTES)
            val headEnd = (first + headNeed - 1).coerceAtMost(last)
            // 片尾：至少 tailNeed 块；小文件时与片头重叠则整文件优先
            val span = last - first + 1
            val tailStart = if (span <= headNeed + tailNeed) {
                (headEnd + 1).coerceAtMost(last)
            } else {
                (last - tailNeed + 1).coerceAtLeast(headEnd + 1)
            }
            FilePieceRange(first, last, headEnd, tailStart)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 边下边播优先级（两阶段）：
     *
     * 阶段 A — 片头/片尾未齐：
     *   - 关闭 SEQUENTIAL_DOWNLOAD（顺序会一直拉中段，3MB/s 也播不了）
     *   - 中段 IGNORE，带宽只砸片头 + 片尾（MP4 moov）
     *
     * 阶段 B — 可开播后：
     *   - 打开顺序下载补中段
     *   - 片头/尾保持 TOP，中段 DEFAULT
     */
    private fun applyStreamingPriorities(
        h: TorrentHandle,
        ti: TorrentInfo,
        fileIndex: Int,
        forceTail: Boolean = true
    ) {
        try {
            headNeedPieces = piecesForBytes(ti, HEAD_TARGET_BYTES)
            tailNeedPieces = piecesForBytes(ti, TAIL_TARGET_BYTES)
            val range = filePieceRange(ti, fileIndex) ?: return
            val fileName = try {
                ti.files().fileName(fileIndex)
            } catch (_: Throwable) {
                currentFilePath?.let { File(it).name }
            }
            val wantTail = forceTail || needsTailIndex(fileName)

            // 先统计当前拥有量再决策
            val headHaveNow = countHeadHave(h, ti, fileIndex, headNeedPieces)
            val tailHaveNow = if (wantTail) {
                countTailHave(h, ti, fileIndex, tailNeedPieces)
            } else {
                tailNeedPieces
            }
            val streamReady =
                headHaveNow >= headNeedPieces &&
                    (!wantTail || tailHaveNow >= tailNeedPieces)

            val midStart = range.headEnd + 1
            val midEnd = if (wantTail) range.tailStart - 1 else range.last

            if (!streamReady) {
                // 阶段 A：只下片头+片尾
                try {
                    h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    sequentialEnabled = false
                } catch (_: Throwable) {
                }
                if (midStart <= midEnd) {
                    for (i in midStart..midEnd) {
                        try {
                            h.piecePriority(i, Priority.IGNORE)
                        } catch (_: Throwable) {
                        }
                    }
                }
            } else {
                // 阶段 B：顺序补中段
                try {
                    h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    sequentialEnabled = true
                } catch (_: Throwable) {
                }
                if (midStart <= midEnd) {
                    for (i in midStart..midEnd step 2) {
                        try {
                            h.piecePriority(i, Priority.DEFAULT)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            for (i in range.first..range.headEnd) {
                try {
                    h.piecePriority(i, Priority.TOP_PRIORITY)
                    h.setPieceDeadline(i, (i - range.first) * 30)
                } catch (_: Throwable) {
                }
            }
            if (wantTail && range.tailStart <= range.last && range.tailStart > range.headEnd) {
                var rank = 0
                for (i in range.last downTo range.tailStart) {
                    try {
                        h.piecePriority(i, Priority.TOP_PRIORITY)
                        h.setPieceDeadline(i, rank * 30)
                        rank++
                    } catch (_: Throwable) {
                    }
                }
            }
            Log.i(
                TAG,
                "stream pri file=$fileIndex phase=${if (streamReady) "B-seq" else "A-head+tail"} " +
                    "head=${range.first}..${range.headEnd}($headHaveNow/$headNeedPieces) " +
                    "tail=${if (wantTail) "${range.tailStart}..${range.last}($tailHaveNow/$tailNeedPieces)" else "off"}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "applyStreamingPriorities: ${t.message}")
        }
    }

    private fun countHeadHave(
        h: TorrentHandle,
        ti: TorrentInfo,
        fileIndex: Int,
        need: Int
    ): Int {
        return try {
            val range = filePieceRange(ti, fileIndex) ?: return 0
            var consecutive = 0
            for (i in 0 until need) {
                val p = range.first + i
                if (p > range.last) break
                if (h.havePiece(p)) consecutive++ else break
            }
            consecutive
        } catch (_: Throwable) {
            0
        }
    }

    private fun countTailHave(
        h: TorrentHandle,
        ti: TorrentInfo,
        fileIndex: Int,
        need: Int
    ): Int {
        return try {
            val range = filePieceRange(ti, fileIndex) ?: return 0
            if (need <= 0) return need
            // 片头已覆盖整文件
            if (range.tailStart > range.last || range.tailStart <= range.headEnd &&
                range.headEnd >= range.last
            ) {
                return need
            }
            var consecutive = 0
            for (i in 0 until need) {
                val p = range.last - i
                if (p < range.tailStart || p < range.first) break
                if (h.havePiece(p)) consecutive++ else break
            }
            consecutive
        } catch (_: Throwable) {
            0
        }
    }

    private fun refreshHeadTailHave(h: TorrentHandle, ti: TorrentInfo, fileIndex: Int) {
        headNeedPieces = piecesForBytes(ti, HEAD_TARGET_BYTES)
        tailNeedPieces = piecesForBytes(ti, TAIL_TARGET_BYTES)
        headHavePieces = countHeadHave(h, ti, fileIndex, headNeedPieces)
        val fileName = try {
            ti.files().fileName(fileIndex)
        } catch (_: Throwable) {
            currentFilePath?.let { File(it).name }
        }
        tailHavePieces = if (needsTailIndex(fileName)) {
            countTailHave(h, ti, fileIndex, tailNeedPieces)
        } else {
            // MKV/TS 等索引在前：不卡片尾
            tailNeedPieces
        }
        progressRef.updateAndGet {
            it.copy(
                headHave = headHavePieces,
                headNeed = headNeedPieces,
                tailHave = tailHavePieces,
                tailNeed = if (needsTailIndex(fileName)) tailNeedPieces else 0
            )
        }
    }

    /**
     * 卡住时：片头/尾仍 TOP，中段 DEFAULT，不关顺序下载。
     */
    private fun boostSelectedFilePieces(h: TorrentHandle, ti: TorrentInfo, fileIndex: Int) {
        try {
            val range = filePieceRange(ti, fileIndex) ?: return
            applyStreamingPriorities(h, ti, fileIndex, forceTail = true)
            val fs = ti.files()
            val fileSize = fs.fileSize(fileIndex)
            val pieceLen = ti.pieceLength().coerceAtLeast(1)
            val numPieces = ti.numPieces()
            val startOff = fs.fileOffset(fileIndex)
            // 10%、30% 处少量 DEFAULT，帮助从只有中段的 peer 拿数据
            for (ratio in doubleArrayOf(0.1, 0.3)) {
                val abs = startOff + (fileSize * ratio).toLong()
                val piece = (abs / pieceLen).toInt().coerceIn(0, numPieces - 1)
                if (piece > range.first + 8 && piece < range.tailStart) {
                    try {
                        h.piecePriority(piece, Priority.DEFAULT)
                    } catch (_: Throwable) {
                    }
                }
            }
            val n = ti.numFiles()
            val fp = Array(n) { Priority.IGNORE }
            fp[fileIndex] = Priority.TOP_PRIORITY
            h.prioritizeFiles(fp)
            Log.i(TAG, "boost stall recovery file=$fileIndex range=${range.first}..${range.last}")
        } catch (t: Throwable) {
            Log.w(TAG, "boostSelectedFilePieces: ${t.message}")
        }
    }

    private fun forceConnectDhtPeers(sm: SessionManager, h: TorrentHandle, hash: Sha1Hash) {
        try {
            // 第二个参数为超时秒数，别太长以免卡住原生线程
            val peers = sm.dhtGetPeers(hash, 3)
            var n = 0
            for (ep in peers) {
                try {
                    h.swig().connect_peer(ep.swig())
                    n++
                } catch (_: Throwable) {
                }
            }
            if (n > 0) Log.i(TAG, "connect_peer from DHT: $n")
        } catch (t: Throwable) {
            Log.w(TAG, "dhtGetPeers: ${t.message}")
        }
    }

    private fun forceConnectListedPeers(h: TorrentHandle) {
        try {
            val list = h.peerInfo() ?: return
            var n = 0
            for (pi in list) {
                if (n >= 40) break
                val raw = try {
                    pi.ip()
                } catch (_: Throwable) {
                    null
                } ?: continue
                // 常见格式 ip:port 或 [ipv6]:port
                val ep = parseEndpoint(raw) ?: continue
                try {
                    h.swig().connect_peer(ep.swig())
                    n++
                } catch (_: Throwable) {
                }
            }
            if (n > 0) Log.i(TAG, "connect_peer from list: $n")
        } catch (t: Throwable) {
            Log.w(TAG, "forceConnectListedPeers: ${t.message}")
        }
    }

    private fun parseEndpoint(raw: String): TcpEndpoint? {
        return try {
            val s = raw.trim()
            if (s.startsWith("[")) {
                val close = s.indexOf(']')
                if (close <= 1) return null
                val host = s.substring(1, close)
                val port = s.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
                TcpEndpoint(host, port)
            } else {
                val idx = s.lastIndexOf(':')
                if (idx <= 0) return null
                val host = s.substring(0, idx)
                val port = s.substring(idx + 1).toIntOrNull() ?: return null
                TcpEndpoint(host, port)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 销毁并重建 session，使 socket 在当前 bind 的网络上创建 */
    private fun recreateSessionOnNative(reason: String) {
        val sm = session ?: return
        Log.i(TAG, "recreate session ($reason)")
        try {
            // 不 mark store stopped：外层会重新 add torrent
            try {
                val hash = infoHash
                if (hash != null) {
                    val h = sm.find(hash)
                    if (h != null) sm.remove(h)
                }
            } catch (_: Throwable) {
            }
            try {
                alertListener?.let { sm.removeListener(it) }
            } catch (_: Throwable) {
            }
            alertListener = null
            try {
                sm.stop()
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recreate stop: ${t.message}")
        }
        session = null
        started.set(false)
        infoHash = null
        // currentJobId / path 保留，由 startDownload 重新 add
    }

    private fun ensureStartedOnNative(): SessionManager {
        session?.let { if (it.isRunning) return it }

        val pack = buildFastSettings()

        val s = SessionManager()
        s.start(SessionParams(pack))
        // 再 apply 一次，确保会话参数生效
        try {
            s.applySettings(buildFastSettings())
            s.maxConnections(500)
            s.maxPeers(500)
            s.maxActiveDownloads(8)
            s.maxActiveSeeds(4)
            // 固定常用端口，比随机 0 端口更好穿透/被连接
            s.listenInterfaces("0.0.0.0:6881,[::]:6881")
            if (!s.isDhtRunning) {
                s.startDht()
            }
            Log.i(TAG, "session listen=${try { s.listenInterfaces() } catch (_: Throwable) { "?" }} dht=${try { s.dhtNodes() } catch (_: Throwable) { -1 }}")
        } catch (t: Throwable) {
            Log.w(TAG, "apply fast settings: ${t.message}")
        }

        val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.STATE_UPDATE.swig(),
                AlertType.TORRENT_FINISHED.swig(),
                AlertType.TORRENT_ERROR.swig(),
                AlertType.FILE_COMPLETED.swig()
            )

            override fun alert(alert: Alert<*>) {
                try {
                    when (alert) {
                        is StateUpdateAlert -> onStateUpdate(alert)
                        is TorrentFinishedAlert -> {
                            progressRef.updateAndGet { p ->
                                p.copy(
                                    state = "FINISHED",
                                    progress = 1f,
                                    fileDoneBytes = p.fileTotalBytes,
                                    isFinished = true
                                )
                            }
                            onDownloadFinishedPromote()
                            publishToStore()
                        }
                        else -> {
                            // torrent error etc.
                            val msg = alert.message()
                            if (alert.type() == AlertType.TORRENT_ERROR) {
                                progressRef.updateAndGet {
                                    it.copy(state = "ERROR", error = msg)
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "alert handler: ${t.message}")
                }
            }
        }
        s.addListener(listener)
        alertListener = listener
        session = s
        started.set(true)
        // 定时推送状态；无同伴时低频 reannounce 拉源（不频繁，避免 thrash）
        nativeExecutor.execute(object : Runnable {
            override fun run() {
                if (!started.get()) return
                try {
                    session?.postTorrentUpdates()
                    val p = progressRef.get()
                    val hash = infoHash
                    val smNow = session
                    if (hash != null && smNow != null &&
                        currentJobId != null &&
                        !p.isFinished &&
                        p.state != "IDLE" &&
                        p.state != "STOPPED" &&
                        p.error == null
                    ) {
                        val noData = p.fileDoneBytes <= 0L && p.downloadRate <= 0
                        if (p.numPeers > 0 && noData) {
                            if (stallSinceMs == 0L) stallSinceMs = System.currentTimeMillis()
                        } else if (p.downloadRate > 0 || p.fileDoneBytes > 0L) {
                            stallSinceMs = 0L
                        }
                        // 持续刷新片头/片尾连续进度 + 重申流式优先级
                        // （SEQUENTIAL_DOWNLOAD 会改 piece 优先级，必须周期重申片头/尾）
                        try {
                            val h = smNow.find(hash)
                            val ti = currentInfo
                            if (h != null && ti != null) {
                                refreshHeadTailHave(h, ti, selectedFileIndex)
                                val needStream =
                                    headHavePieces < headNeedPieces ||
                                        tailHavePieces < tailNeedPieces
                                if (needStream || p.fileDoneBytes > 0L) {
                                    applyStreamingPriorities(
                                        h, ti, selectedFileIndex, forceTail = true
                                    )
                                }
                            }
                        } catch (_: Throwable) {
                        }
                        val stalledLong =
                            p.numPeers > 0 && noData &&
                                stallSinceMs > 0L &&
                                System.currentTimeMillis() - stallSinceMs >= 12_000L
                        val needPeers = p.numPeers <= 0 && p.downloadRate <= 0
                        val needHead =
                            p.numPeers > 0 && headHavePieces < headNeedPieces &&
                                p.fileDoneBytes > 0L // 有数据但片头不连续
                        val needTail =
                            p.numPeers > 0 && tailHavePieces < tailNeedPieces &&
                                p.fileDoneBytes > 0L
                        if (needPeers || stalledLong || needHead || needTail) {
                            try {
                                stabilizeAndBoost(
                                    smNow, hash,
                                    force = stalledLong || needHead || needTail
                                )
                            } catch (t: Throwable) {
                                Log.w(TAG, "periodic boost: ${t.message}")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "postTorrentUpdates: ${t.message}")
                }
                if (started.get()) {
                    nativeExecutor.execute {
                        try {
                            Thread.sleep(1500)
                        } catch (_: InterruptedException) {
                        }
                        if (started.get()) run()
                    }
                }
            }
        })
        Log.i(TAG, "session started (fast profile)")
        return s
    }

    /** 高速下载参数：多连接、快建连、全 Tracker 汇报、DHT/LSD/UPnP */
    private fun buildFastSettings(): SettingsPack {
        return SettingsPack().apply {
            // —— 连接数 / 活跃任务 ——
            connectionsLimit(500)
            maxPeerlistSize(4000)
            // 单任务下载：active 设大一点 + 关闭 auto_manage，避免排队 pause/resume
            activeDownloads(20)
            activeSeeds(10)
            activeLimit(30)
            setBoolean(settings_pack.bool_types.dont_count_slow_torrents.swigValue(), true)
            setBoolean(settings_pack.bool_types.auto_manage_prefer_seeds.swigValue(), false)
            activeDhtLimit(500)
            activeTrackerLimit(200)
            activeLsdLimit(100)
            downloadRateLimit(0) // 不限速
            uploadRateLimit(0)

            // —— DHT / 本地发现 / 端口映射 ——
            setEnableDht(true)
            setEnableLsd(true)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            setDhtBootstrapNodes(
                listOf(
                    "router.bittorrent.com:6881",
                    "router.utorrent.com:6881",
                    "dht.transmissionbt.com:6881",
                    "router.bitcomet.com:6881",
                    "dht.libtorrent.org:25401",
                    "dht.aelitis.com:6881"
                ).joinToString(",")
            )

            // —— 传输协议 ——
            // TCP + uTP 都开；代理环境下 TCP 往往更稳
            setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_outgoing_tcp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_incoming_tcp.swigValue(), true)
            setBoolean(settings_pack.bool_types.prefer_udp_trackers.swigValue(), false)
            setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true)
            setBoolean(settings_pack.bool_types.smooth_connects.swigValue(), false) // 更快建连
            setBoolean(settings_pack.bool_types.anonymous_mode.swigValue(), false)
            setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), true)
            setBoolean(settings_pack.bool_types.rate_limit_ip_overhead.swigValue(), false)

            // —— 建连/请求节奏（更激进） ——
            setInteger(settings_pack.int_types.connection_speed.swigValue(), 80)
            setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 10)
            setInteger(settings_pack.int_types.request_timeout.swigValue(), 30)
            setInteger(settings_pack.int_types.piece_timeout.swigValue(), 20)
            setInteger(settings_pack.int_types.inactivity_timeout.swigValue(), 40)
            setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 3)
            setInteger(settings_pack.int_types.max_failcount.swigValue(), 5)
            setInteger(settings_pack.int_types.num_want.swigValue(), 200)
            setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), 100)
            setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), 50)
            setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), 1500)
            setInteger(settings_pack.int_types.max_allowed_in_request_queue.swigValue(), 2000)
            setInteger(settings_pack.int_types.min_announce_interval.swigValue(), 30)
            setInteger(settings_pack.int_types.dht_announce_interval.swigValue(), 10)
            setInteger(settings_pack.int_types.aio_threads.swigValue(), 4)
            setInteger(settings_pack.int_types.send_buffer_watermark.swigValue(), 3 * 1024 * 1024)
            setInteger(settings_pack.int_types.send_buffer_low_watermark.swigValue(), 512 * 1024)
            setInteger(settings_pack.int_types.max_peer_recv_buffer_size.swigValue(), 2 * 1024 * 1024)

            listenInterfaces("0.0.0.0:6881,[::]:6881")
            alertQueueSize(4000)
        }
    }

    private fun statusHashMatches(st: org.libtorrent4j.TorrentStatus, want: Sha1Hash): Boolean {
        try {
            val ih = st.getInfoHashes() ?: return false
            val best = try {
                ih.getBest()
            } catch (_: Throwable) {
                null
            }
            if (best != null && best.equals(want)) return true
            val v1 = try {
                ih.getV1()
            } catch (_: Throwable) {
                null
            }
            if (v1 != null && !v1.isAllZeros && v1.equals(want)) return true
            // hex 兜底
            val wantHex = want.toHex()
            if (best != null && best.toHex().equals(wantHex, ignoreCase = true)) return true
            if (v1 != null && v1.toHex().equals(wantHex, ignoreCase = true)) return true
        } catch (_: Throwable) {
        }
        return false
    }

    private fun onStateUpdate(alert: StateUpdateAlert) {
        val want = infoHash
        val statuses = try {
            alert.status()
        } catch (t: Throwable) {
            Log.w(TAG, "stateUpdate.status: ${t.message}")
            return
        }
        if (statuses.isEmpty()) return
        val ti = currentInfo
        val idx = selectedFileIndex
        val fileTotal = try {
            ti?.files()?.fileSize(idx) ?: 0L
        } catch (_: Throwable) {
            0L
        }
        val dhtNodes = try {
            session?.dhtNodes() ?: 0L
        } catch (_: Throwable) {
            0L
        }
        val netHint = try {
            appContext?.let { TorrentNetwork.proxyHint(it) }
        } catch (_: Throwable) {
            null
        }

        for (st in statuses) {
            try {
                // 单任务时：hash 对不上也接受（避免 v1/v2 比较失败导致永远 0）
                val matched = when {
                    want == null -> currentJobId != null
                    statusHashMatches(st, want) -> true
                    statuses.size == 1 && currentJobId != null -> true
                    else -> false
                }
                if (!matched) continue

                // 只读 alert 里的 status 快照，不碰 TorrentHandle
                val done = st.totalWantedDone()
                val wanted = st.totalWanted().coerceAtLeast(1)
                val fileDone = done.coerceAtMost(if (fileTotal > 0) fileTotal else done)
                val finished = st.isFinished || (fileTotal > 0 && fileDone >= fileTotal)
                // 展示用：连接中同伴 + 列表中同伴（找源阶段 listPeers 更有意义）
                val peersConnected = st.numPeers()
                val peersListed = try {
                    st.listPeers()
                } catch (_: Throwable) {
                    0
                }
                val seedsListed = try {
                    st.listSeeds()
                } catch (_: Throwable) {
                    0
                }
                val candidates = try {
                    st.connectCandidates()
                } catch (_: Throwable) {
                    0
                }
                val tracker = try {
                    st.currentTracker().orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                progressRef.set(
                    Progress(
                        name = st.name() ?: ti?.name().orEmpty(),
                        state = if (finished) "FINISHED" else (st.state()?.name ?: "DOWNLOADING"),
                        progress = if (finished) 1f else st.progress(),
                        downloadRate = st.downloadPayloadRate(),
                        numPeers = peersConnected.coerceAtLeast(0),
                        numSeeds = st.numSeeds(),
                        fileDoneBytes = fileDone,
                        fileTotalBytes = if (fileTotal > 0) fileTotal else wanted,
                        totalWantedDone = done,
                        totalWanted = wanted,
                        isFinished = finished,
                        error = null,
                        listPeers = peersListed,
                        listSeeds = seedsListed,
                        connectCandidates = candidates,
                        dhtNodes = dhtNodes,
                        currentTracker = tracker,
                        announcingToTrackers = try {
                            st.announcingToTrackers()
                        } catch (_: Throwable) {
                            false
                        },
                        announcingToDht = try {
                            st.announcingToDht()
                        } catch (_: Throwable) {
                            false
                        },
                        networkHint = netHint,
                        headHave = headHavePieces,
                        headNeed = headNeedPieces,
                        tailHave = tailHavePieces,
                        tailNeed = run {
                            val fn = try {
                                ti?.files()?.fileName(idx)
                            } catch (_: Throwable) {
                                currentFilePath?.let { File(it).name }
                            }
                            if (needsTailIndex(fn)) tailNeedPieces else 0
                        }
                    )
                )
                publishToStore()
                if (finished) {
                    onDownloadFinishedPromote()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "parse status: ${t.message}")
            }
        }
    }

    /** 用户主动停止当前下载（离开页面不会调用） */
    fun stopCurrent(removeData: Boolean = false) {
        val jobId = currentJobId
        val done = CountDownLatch(1)
        nativeExecutor.execute {
            try {
                stopCurrentOnNative(markStoreStopped = true)
            } finally {
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        jobId?.let { TorrentDownloadStore.markStopped(it) }
    }

    private fun stopCurrentOnNative(markStoreStopped: Boolean) {
        val sm = session
        val hash = infoHash
        val jobId = currentJobId
        infoHash = null
        currentInfo = null
        currentJobId = null
        currentFilePath = null
        if (sm != null && hash != null) {
            try {
                val h = try {
                    sm.find(hash)
                } catch (t: Throwable) {
                    Log.w(TAG, "find for remove: ${t.message}")
                    null
                }
                if (h != null) {
                    try {
                        sm.remove(h)
                    } catch (t: Throwable) {
                        Log.w(TAG, "remove: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "stopCurrent: ${t.message}")
            }
        }
        if (markStoreStopped && jobId != null) {
            // store 在外层 mark，这里只清引擎
        }
        progressRef.set(Progress.empty())
    }

    fun shutdown() {
        started.set(false)
        val done = CountDownLatch(1)
        nativeExecutor.execute {
            try {
                stopCurrentOnNative(markStoreStopped = true)
                try {
                    alertListener?.let { session?.removeListener(it) }
                } catch (_: Throwable) {
                }
                alertListener = null
                try {
                    session?.stop()
                } catch (_: Throwable) {
                }
                session = null
            } finally {
                done.countDown()
            }
        }
        done.await(8, TimeUnit.SECONDS)
    }

    fun resolveFile(dir: File, ti: TorrentInfo, fileIndex: Int): File {
        val rel = ti.files().filePath(fileIndex)
        return File(dir, rel)
    }
}
