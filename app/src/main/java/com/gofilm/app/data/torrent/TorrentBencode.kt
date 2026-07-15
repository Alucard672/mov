package com.gofilm.app.data.torrent

import java.io.ByteArrayInputStream
import java.nio.charset.Charset

data class TorrentFileItem(
    val index: Int,
    val path: String,
    val size: Long,
    val isVideo: Boolean
)

/**
 * 极简 bencode 解析，仅用于展示 .torrent 内文件列表（不依赖原生库）。
 */
object TorrentBencode {

    data class ParsedTorrent(
        val name: String,
        val files: List<TorrentFileItem>,
        val totalSize: Long
    )

    fun parse(bytes: ByteArray): ParsedTorrent {
        val root = decode(ByteArrayInputStream(bytes)) as? Map<*, *>
            ?: error("种子格式无效")
        val info = root["info"] as? Map<*, *> ?: error("缺少 info 字段")
        val name = (info["name"] as? ByteArray)?.toString(Charset.forName("UTF-8"))
            ?: (info["name.utf-8"] as? ByteArray)?.toString(Charset.forName("UTF-8"))
            ?: "未命名种子"

        val files = mutableListOf<TorrentFileItem>()
        val multi = info["files"] as? List<*>
        if (multi != null) {
            multi.forEachIndexed { index, item ->
                val m = item as? Map<*, *> ?: return@forEachIndexed
                val length = (m["length"] as? Long) ?: (m["length"] as? Int)?.toLong() ?: 0L
                val pathList = m["path"] as? List<*> ?: m["path.utf-8"] as? List<*>
                val path = pathList?.mapNotNull {
                    when (it) {
                        is ByteArray -> it.toString(Charset.forName("UTF-8"))
                        is String -> it
                        else -> null
                    }
                }?.joinToString("/") ?: "file_$index"
                files += TorrentFileItem(
                    index = index,
                    path = path,
                    size = length,
                    isVideo = isVideo(path)
                )
            }
        } else {
            val length = (info["length"] as? Long) ?: (info["length"] as? Int)?.toLong() ?: 0L
            files += TorrentFileItem(0, name, length, isVideo(name))
        }

        val sorted = files.sortedWith(
            compareByDescending<TorrentFileItem> { it.isVideo }.thenByDescending { it.size }
        )
        return ParsedTorrent(name = name, files = sorted, totalSize = files.sumOf { it.size })
    }

    private fun isVideo(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "mpeg", "mpg", "3gp"
        )
    }

    private fun decode(input: ByteArrayInputStream): Any {
        val c = input.read()
        if (c < 0) error("unexpected eof")
        return when (c.toChar()) {
            'i' -> {
                val sb = StringBuilder()
                while (true) {
                    val ch = input.read()
                    if (ch < 0) error("bad int")
                    if (ch.toChar() == 'e') break
                    sb.append(ch.toChar())
                }
                sb.toString().toLong()
            }
            'l' -> {
                val list = mutableListOf<Any>()
                while (true) {
                    input.mark(1)
                    val n = input.read()
                    if (n < 0) error("bad list")
                    if (n.toChar() == 'e') break
                    input.reset()
                    list += decode(input)
                }
                list
            }
            'd' -> {
                val map = linkedMapOf<String, Any>()
                while (true) {
                    input.mark(1)
                    val n = input.read()
                    if (n < 0) error("bad dict")
                    if (n.toChar() == 'e') break
                    input.reset()
                    val key = decode(input) as ByteArray
                    val value = decode(input)
                    map[key.toString(Charsets.ISO_8859_1)] = value
                }
                map
            }
            in '0'..'9' -> {
                val sb = StringBuilder().append(c.toChar())
                while (true) {
                    val ch = input.read()
                    if (ch < 0) error("bad str")
                    if (ch.toChar() == ':') break
                    sb.append(ch.toChar())
                }
                val len = sb.toString().toInt()
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val r = input.read(buf, off, len - off)
                    if (r < 0) error("bad str body")
                    off += r
                }
                buf
            }
            else -> error("bad bencode token ${c.toChar()}")
        }
    }
}
