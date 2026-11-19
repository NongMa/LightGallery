package com.liu.gallery.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 实况照片（Live Photo / 动态照片）支持。
 *
 * 识别原理：主流“动态照片”都是在 JPEG 尾部内嵌一段 MP4（Google Motion Photo 与
 * 小米/三星等 OEM 均为此格式），并在开头的 XMP 元数据里写入 MotionPhoto / MicroVideo
 * 标记。这里只解析 JPEG 头部若干段（通常几 KB），命中率高、开销极低，
 * 结果按条目 id 记忆化，滚动时不会重复读文件。
 *
 * 播放时按需把尾部的 MP4 抽取到应用缓存目录，交给 ExoPlayer 循环播放。
 */
object LivePhotoLoader {

    private const val TAG = "LivePhoto"

    /** XMP 标记最多在文件前 512KB 内出现 */
    private const val MAX_SCAN = 512 * 1024

    private val liveCache = ConcurrentHashMap<Long, Boolean>()
    private val videoCache = ConcurrentHashMap<Long, File>()

    // 无上界的 ConcurrentHashMap 会随滚动无限增长，这里设硬上限（超限整体清空，代价是重新检测）
    private const val MAX_LIVE_CACHE = 8192
    private const val MAX_VIDEO_CACHE = 256

    /** 是否为实况照片（仅 JPEG 参与检测） */
    suspend fun isLivePhoto(context: Context, item: MediaItem): Boolean =
        withContext(Dispatchers.IO) {
            if (item.isVideo || !isJpeg(item)) return@withContext false
            liveCache[item.id] ?: detect(context, item).also {
                if (liveCache.size >= MAX_LIVE_CACHE) liveCache.clear()
                liveCache[item.id] = it
            }
        }

    /** 非挂起版本：仅在已命中缓存时可靠，UI 层优先用挂起版本 */
    fun isLiveCached(item: MediaItem): Boolean = liveCache[item.id] == true

    /**
     * 一次性把给定条目全部识别完并返回实况照片 id 集（供"仅实况"过滤用）。
     * 结果写入 liveCache，之后滚动/详情页的单张检测都是缓存命中。
     * 调用方需在 IO 调度器上执行；JPEGs 之外的条目直接跳过。
     */
    fun scanLiveIds(context: Context, items: List<MediaItem>): Set<Long> {
        val out = HashSet<Long>()
        for (item in items) {
            if (item.isVideo || !isJpeg(item)) continue
            val live = liveCache[item.id] ?: detect(context, item).also { liveCache[item.id] = it }
            if (live) out.add(item.id)
        }
        return out
    }

    private fun isJpeg(item: MediaItem): Boolean =
        item.mimeType.equals("image/jpeg", ignoreCase = true) ||
            item.name.endsWith(".jpg", ignoreCase = true) ||
            item.name.endsWith(".jpeg", ignoreCase = true)

    // ---------------------------------------------------------------- 检测

    private fun detect(context: Context, item: MediaItem): Boolean = try {
        val input = context.contentResolver.openInputStream(item.uri) ?: return false
        input.use { stream -> scanForMotionXmp(stream) }
    } catch (e: Exception) {
        Log.d(TAG, "detect failed for ${item.id}: $e")
        false
    }

    /**
     * 顺序解析 JPEG 段（APPn 元数据）寻找 XMP：
     * - 只顺序读取到需要的位置，普通照片通常只需几 KB；
     * - 段落超出缓冲时按需扩容继续读（总量不超过 MAX_SCAN）；
     * - 命中 XMP 段即判定是否含 MotionPhoto / MicroVideo 标记。
     */
    private fun scanForMotionXmp(stream: java.io.InputStream): Boolean {
        var buf = ByteArray(16 * 1024)
        var total = 0

        fun ensure(capacity: Int): Boolean {
            if (capacity > MAX_SCAN) return false
            if (capacity > buf.size) {
                val grown = ByteArray(maxOf(capacity, buf.size * 2, 16 * 1024))
                System.arraycopy(buf, 0, grown, 0, total)
                buf = grown
            }
            while (total < capacity) {
                val r = stream.read(buf, total, buf.size - total)
                if (r < 0) return false
                total += r
            }
            return true
        }

        if (!ensure(4)) return false
        if (buf[0] != 0xFF.toByte() || buf[1] != 0xD8.toByte()) return false

        var pos = 2 // 跳过 SOI
        while (ensure(pos + 4)) {
            if (buf[pos] != 0xFF.toByte()) {
                pos++
                continue
            }
            var marker = buf[pos + 1].toInt() and 0xFF
            while (marker == 0xFF && ensure(pos + 6)) {
                pos++
                marker = buf[pos + 1].toInt() and 0xFF
            }
            // SOS/EOI 之后是压缩数据，后面不可能再有元数据
            if (marker == 0xDA || marker == 0xD9 || marker == 0x00) return false

            val len = ((buf[pos + 2].toInt() and 0xFF) shl 8) or
                (buf[pos + 3].toInt() and 0xFF)
            if (len < 2) return false
            val segStart = pos + 4
            val segEnd = pos + 2 + len
            if (segEnd <= segStart) return false

            // 按需把整个段读进缓冲（上限 MAX_SCAN）
            if (!ensure(segEnd)) return false

            if (marker == 0xE1 && isXmpSegment(buf, segStart, segEnd) &&
                containsMotionMarker(buf, segStart, segEnd)
            ) {
                return true
            }
            pos = segEnd
        }
        return false
    }

    private fun isXmpSegment(buf: ByteArray, start: Int, end: Int): Boolean {
        val ns = "http://ns.adobe.com/xap/1.0/"
        if (start + ns.length + 1 > end) return false
        for (i in ns.indices) {
            if (buf[start + i] != ns[i].code.toByte()) return false
        }
        return true
    }

    /** XMP 中出现任一动态照片标记即判定为实况照片 */
    private fun containsMotionMarker(buf: ByteArray, start: Int, end: Int): Boolean {
        val markers = listOf("MotionPhoto", "MicroVideo")
        for (marker in markers) {
            val first = marker[0].code.toByte()
            val limit = end - marker.length
            for (i in start..limit) {
                if (buf[i] != first) continue
                var hit = true
                for (j in 1 until marker.length) {
                    if (buf[i + j] != marker[j].code.toByte()) {
                        hit = false
                        break
                    }
                }
                if (hit) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------ 抽取视频

    /**
     * 把内嵌 MP4 抽取到缓存目录并返回文件；失败返回 null。
     * 通过随机访问文件定位尾部 ftyp 盒（JPEG 正常数据不会出现该序列）。
     */
    suspend fun extractVideo(context: Context, item: MediaItem): File? =
        withContext(Dispatchers.IO) {
            if (!isLivePhoto(context, item)) return@withContext null
            videoCache[item.id]?.takeIf { it.exists() }?.let { return@withContext it }

            val pfd = try {
                context.contentResolver.openFileDescriptor(item.uri, "r")
            } catch (e: Exception) {
                null
            } ?: return@withContext null

            try {
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    val size = channel.size()
                    if (size < 4096) return@withContext null

                    // 读尾部最多 2MB 找 ftyp
                    val tailSize = minOf(size, 2L * 1024 * 1024).toInt()
                    val tail = ByteArray(tailSize)
                    channel.position(size - tailSize)
                    var off = 0
                    while (off < tailSize) {
                        val r = channel.read(java.nio.ByteBuffer.wrap(tail, off, tailSize - off))
                        if (r < 0) break
                        off += r
                    }

                    val idx = lastIndexOf(tail, off, byteArrayOf(
                        'f'.code.toByte(), 't'.code.toByte(),
                        'y'.code.toByte(), 'p'.code.toByte(),
                    ))
                    if (idx < 4) return@withContext null

                    val boxStartInTail = idx - 4
                    val boxSize = ((tail[boxStartInTail].toInt() and 0xFF) shl 24) or
                        ((tail[boxStartInTail + 1].toInt() and 0xFF) shl 16) or
                        ((tail[boxStartInTail + 2].toInt() and 0xFF) shl 8) or
                        (tail[boxStartInTail + 3].toInt() and 0xFF)
                    // ftyp 盒通常 24~32 字节；数据段里碰巧出现的 ftyp 很难满足该约束
                    if (boxSize !in 16..4096) return@withContext null

                    val videoStart = size - tailSize + boxStartInTail
                    val videoLength = size - videoStart
                    if (videoLength < 1024) return@withContext null

                    val out = File(context.cacheDir, "live_${item.id}_${item.dateModifiedMillis}.mp4")
                    if (!out.exists() || out.length() != videoLength) {
                        FileOutputStream(out).use { fos ->
                            val dst = fos.channel
                            var transferred = 0L
                            val buf = java.nio.ByteBuffer.allocate(256 * 1024)
                            channel.position(videoStart)
                            while (transferred < videoLength) {
                                buf.clear()
                                buf.limit(minOf(buf.capacity().toLong(), videoLength - transferred).toInt())
                                val r = channel.read(buf)
                                if (r <= 0) break
                                buf.flip()
                                while (buf.hasRemaining()) dst.write(buf)
                                transferred += r
                            }
                            dst.force(true)
                        }
                    }
                    if (videoCache.size >= MAX_VIDEO_CACHE) videoCache.clear()
                    videoCache[item.id] = out
                    out
                }
            } catch (e: Exception) {
                Log.w(TAG, "extract failed for ${item.id}: $e")
                null
            } finally {
                try {
                    pfd.close()
                } catch (e: Exception) {
                }
            }
        }

    private fun lastIndexOf(buf: ByteArray, length: Int, pattern: ByteArray): Int {
        for (i in (length - pattern.size) downTo 0) {
            var hit = true
            for (j in pattern.indices) {
                if (buf[i + j] != pattern[j]) {
                    hit = false
                    break
                }
            }
            if (hit) return i
        }
        return -1
    }

    /** 设置页“清理缓存”：清空内存中的实况识别/抽取文件引用 */
    fun clearInMemory() {
        liveCache.clear()
        videoCache.clear()
    }

    /** 条目被删除时清理缓存 */
    fun evict(item: MediaItem) {
        liveCache.remove(item.id)
        videoCache.remove(item.id)?.delete()
    }

    /**
     * 清理磁盘上残留的实况抽取文件：文件名 live_{id}_{mtime}.mp4，
     * 凡 id 不在当前媒体列表中即为可删的脏数据。
     */
    suspend fun clearStale(context: Context, validIds: Set<Long>) = withContext(Dispatchers.IO) {
        try {
            val stale = context.cacheDir.listFiles { f ->
                f.isFile && f.name.startsWith("live_") && f.name.endsWith(".mp4")
            } ?: return@withContext
            for (file in stale) {
                val id = file.name.removePrefix("live_").substringBefore('_').toLongOrNull()
                if (id == null || id !in validIds) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearStale failed: $e")
        }
    }
}
