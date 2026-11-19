package com.liu.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本机媒体仓库：只走 MediaStore，不联网。
 * 查询采用“逐级降级投影”策略：部分定制 ROM 的列不存在会导致查询抛异常，
 * 因此先用全量列，失败后逐级退回精简列，保证任何机型都能拿到数据。
 */
class MediaRepository(private val context: Context) {

    private val cr: ContentResolver get() = context.contentResolver

    // ---------------------------------------------------------------- 查询

    /** 读取本机全部图片与视频 */
    suspend fun loadMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        val images = queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE)
        val videos = queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO)
        Log.d(TAG, "loadMedia: images=${images.size} videos=${videos.size}")
        // 统一按拍摄时间倒序，再按 id 倒序（同刻新增的文件顺序稳定）
        (images + videos).sortedWith(
            compareByDescending<MediaItem> { it.dateTakenMillis }.thenByDescending { it.id }
        )
    }

    private fun queryCollection(uri: Uri, type: MediaType): List<MediaItem> {
        Log.d(TAG, "queryCollection: uri=$uri type=$type")
        val projection = buildList {
            add("_id")
            add("_display_name")
            add("mime_type")
            add("size")
            add("date_added")
            add("date_modified")
            add("_data")
            add("width")
            add("height")
            add("bucket_id")
            add("bucket_display_name")
            add("datetaken")
            add("orientation")
            if (type == MediaType.VIDEO) add("duration")
            if (Build.VERSION.SDK_INT >= 29) add("relative_path")
            if (Build.VERSION.SDK_INT >= 30) add("is_favorite")
        }.toMutableList()

        // 逐列降级：部分 ROM 会拒绝个别列（如本机的 "size"），
        // 每次按报错剔除非法列后重试，直到查询成功或只剩必需列。
        while (projection.isNotEmpty()) {
            val cursor = try {
                cr.query(uri, projection.toTypedArray(), null, null, null)
            } catch (e: IllegalArgumentException) {
                val bad = Regex("Invalid column ([\\w.]+)")
                    .find(e.message.orEmpty())?.groupValues?.get(1)
                if (bad == null || !projection.remove(bad)) {
                    Log.w(TAG, "query unresolvable: $e")
                    return emptyList()
                }
                Log.w(TAG, "drop unsupported column '$bad' on $uri")
                continue
            } catch (e: Exception) {
                Log.w(TAG, "query failed: $e")
                return emptyList()
            }

            if (cursor == null) {
                Log.w(TAG, "query returned null for $uri")
                return emptyList()
            }
            cursor.use {
                val result = try {
                    parseCursor(it, uri, type)
                } catch (e: Exception) {
                    Log.w(TAG, "parseCursor failed: $e")
                    null
                }
                if (result != null) {
                    Log.d(TAG, "query ok: cols=${it.columnCount} rows=${result.size}")
                    return result
                }
            }
            return emptyList()
        }
        return emptyList()
    }

    private fun parseCursor(cursor: Cursor, collection: Uri, type: MediaType): List<MediaItem> {
        // 列索引安全映射：列缺失时为 -1，读取处兜底
        val idx = HashMap<String, Int>(16)
        for (i in 0 until cursor.columnCount) {
            idx[cursor.getColumnName(i)] = i
        }
        fun i(name: String): Int = idx[name] ?: -1
        fun longOf(name: String): Long {
            val p = i(name)
            return if (p >= 0 && !cursor.isNull(p)) cursor.getLong(p) else 0L
        }
        fun strOf(name: String): String? {
            val p = i(name)
            return if (p >= 0 && !cursor.isNull(p)) cursor.getString(p) else null
        }

        val idP = i("_id")
        if (idP < 0) return emptyList()

        val list = ArrayList<MediaItem>(cursor.count)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idP)
            val name = strOf("_display_name") ?: "media_$id"
            val mimeType = strOf("mime_type")
                ?: if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg"
            val size = longOf("size")
            val width = longOf("width").toInt()
            val height = longOf("height").toInt()
            val dateAdded = longOf("date_added") * 1000L
            val dateModified = longOf("date_modified") * 1000L
            val dateTakenRaw = longOf("datetaken")
            val filePath = strOf("_data")
            val relativePath = strOf("relative_path")?.trimEnd('/')
                ?: filePath?.substringBeforeLast('/', "") ?: ""

            // 拍摄时间兜底：datetaken → 文件修改时间 → 入库时间
            val dateTaken = when {
                dateTakenRaw > 0 -> dateTakenRaw
                dateModified > 0 -> dateModified
                else -> dateAdded
            }

            val bucketName = strOf("bucket_display_name")
                ?: relativePath.trimEnd('/').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() }
                ?: "未命名相册"

            val orientationP = i("orientation")
            val orientation = if (orientationP >= 0 && !cursor.isNull(orientationP)) {
                cursor.getInt(orientationP)
            } else 0

            val favP = i("is_favorite")
            val favorite = favP >= 0 && !cursor.isNull(favP) && cursor.getInt(favP) == 1

            list.add(
                MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = name,
                    relativePath = relativePath,
                    filePath = filePath,
                    albumId = strOf("bucket_id") ?: relativePath,
                    albumName = bucketName,
                    mimeType = mimeType,
                    sizeBytes = size,
                    width = width,
                    height = height,
                    dateTakenMillis = dateTaken,
                    dateModifiedMillis = dateModified,
                    durationMillis = if (type == MediaType.VIDEO) longOf("duration") else 0L,
                    orientation = orientation,
                    type = type,
                    isFavorite = favorite,
                )
            )
        }
        return list
    }

    // ------------------------------------------------------------ 修改：删除

    /** Android 11+：申请系统弹窗确认后移入回收站 */
    fun createTrashIntent(uris: List<Uri>): PendingIntent? {
        if (Build.VERSION.SDK_INT < 30 || uris.isEmpty()) return null
        return try {
            MediaStore.createTrashRequest(cr, uris, true)
        } catch (e: Exception) {
            null
        }
    }

    /** Android 9 及以下：持有写权限时直接删除 */
    suspend fun deleteDirect(items: List<MediaItem>): Boolean = withContext(Dispatchers.IO) {
        var allOk = true
        for (item in items) {
            var ok = false
            try {
                ok = cr.delete(item.uri, null, null) > 0
            } catch (e: SecurityException) {
                ok = false
            }
            if (!ok) {
                // 兜底：直接删文件并让媒体库感知（仅限可直写的旧版本存储）
                val path = item.filePath
                if (path != null) {
                    try {
                        ok = File(path).delete()
                        if (ok) {
                            cr.delete(
                                ContentUris.withAppendedId(
                                    if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    item.id
                                ), null, null
                            )
                        }
                    } catch (e: Exception) {
                        ok = false
                    }
                }
            }
            if (!ok) allOk = false
        }
        allOk
    }

    // ---------------------------------------------------------- 修改：收藏

    /** 尝试直接改收藏（文件归属应用或系统允许时无需弹窗） */
    suspend fun setFavoriteDirect(uris: List<Uri>, favorite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 30) return@withContext false
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0)
            }
            try {
                uris.all { cr.update(it, values, null, null) > 0 }
            } catch (e: Exception) {
                false
            }
        }

    /** Android 12+：系统确认弹窗方式改收藏 */
    fun createFavoriteIntent(uris: List<Uri>, favorite: Boolean): PendingIntent? {
        if (Build.VERSION.SDK_INT < 31 || uris.isEmpty()) return null
        return try {
            MediaStore.createFavoriteRequest(cr, uris, favorite)
        } catch (e: Exception) {
            null
        }
    }

    /** 授权成功后真正写入收藏标记 */
    suspend fun applyFavorite(uris: List<Uri>, favorite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 30) return@withContext false
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0)
            }
            uris.all { uri ->
                try {
                    cr.update(uri, values, null, null) > 0
                } catch (e: Exception) {
                    false
                }
            }
        }

    // ---------------------------------------------------------- 修改：重命名

    /** Android 10 及以下：直接改名（需写权限） */
    suspend fun renameDirect(item: MediaItem, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            try {
                cr.update(item.uri, values, null, null) > 0
            } catch (e: Exception) {
                // 再尝试改文件本身（旧版存储）
                val path = item.filePath
                if (path != null && newName.isNotEmpty()) {
                    try {
                        val dir = File(path).parent ?: return@withContext false
                        val target = File(dir, newName)
                        File(path).renameTo(target)
                    } catch (e2: Exception) {
                        false
                    }
                } else false
            }
        }

    /** Android 11+：申请系统确认后改名 */
    fun createWriteIntent(uris: List<Uri>): PendingIntent? {
        if (Build.VERSION.SDK_INT < 30 || uris.isEmpty()) return null
        return try {
            MediaStore.createWriteRequest(cr, uris)
        } catch (e: Exception) {
            null
        }
    }

    /** 授权成功后执行改名 */
    suspend fun applyRename(uri: Uri, newName: String): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
        }
        try {
            cr.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "MediaRepo"

        /** 生成待确认的写操作请求（UI 层统一转发给系统） */
        const val REQUEST_NONE = 0
    }
}
