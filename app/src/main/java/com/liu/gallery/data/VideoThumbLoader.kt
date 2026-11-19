package com.liu.gallery.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频缩略图加载器：系统取帧 + 内存 LRU 缓存。
 * 视频帧无法像图片那样交给图片库直接解码，这里统一在 IO 线程取帧，
 * 按“条目 id + 修改时间 + 目标尺寸”缓存，滚动时直接命中内存。
 */
object VideoThumbLoader {

    private const val TAG = "VideoThumb"

    // 用最大内存的 1/8 做缓存，单位 KB
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceAtLeast(4096)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            value.allocationByteCount.coerceAtLeast(1) / 1024
    }

    suspend fun load(context: Context, item: MediaItem, targetPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "${item.id}_${item.dateModifiedMillis}_$targetPx"
            cache.get(key) ?: fetch(context, item, targetPx)?.also { cache.put(key, it) }
        }

    private fun fetch(context: Context, item: MediaItem, targetPx: Int): Bitmap? {
        if (targetPx <= 0) return null
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(
                    item.uri, Size(targetPx, targetPx), null
                )
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    item.id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 条目被删除/改名后调用，避免脏缓存：按 “id_mtime_” 前缀精确清理该条目的全部尺寸 */
    fun evict(item: MediaItem) {
        val prefix = "${item.id}_${item.dateModifiedMillis}_"
        val snapshot = cache.snapshot()
        if (snapshot.isNullOrEmpty()) return
        for (key in snapshot.keys) {
            if (key.startsWith(prefix)) cache.remove(key)
        }
    }

    /** 设置页“清理缓存”：整体清空内存帧缓存 */
    fun clear() = cache.evictAll()

    @Suppress("unused")
    private const val LOG_TAG = TAG
}
