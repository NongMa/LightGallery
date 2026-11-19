package com.liu.gallery.data

import android.net.Uri
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 媒体类型 */
enum class MediaType { IMAGE, VIDEO }

/** 时间线分组粒度：日 / 月 / 年 */
enum class GroupMode { DAY, MONTH, YEAR }

/**
 * 筛选条件；UI 层按单条件语义使用（顶部 chip 条）：任一条件独占，取消筛选即全空。
 * matches 仍按"各组取交集、空 = 不限"实现，以便未来需要时可恢复复合筛选。
 */
data class MediaFilter(
    /** 媒体类型多选；空 = 图片和视频都要 */
    val types: Set<MediaType> = emptySet(),
    /** 仅收藏 */
    val favoritesOnly: Boolean = false,
    /** 年份多选；空 = 全部年份 */
    val years: Set<Int> = emptySet(),
    /** 仅实况照片 */
    val liveOnly: Boolean = false,
) {
    /** 是否有任何条件生效（用于按钮高亮与"清除"的可用态） */
    val isActive: Boolean get() = types.isNotEmpty() || favoritesOnly || years.isNotEmpty() || liveOnly

    /**
     * [liveIds] 为已识别出的实况照片 id 集；liveOnly 生效但尚未识别完成（null）时，
     * 任何条目都不匹配——由 UI 显示"正在识别实况照片…"，避免闪出未过滤的列表。
     */
    fun matches(item: MediaItem, liveIds: Set<Long>? = null): Boolean {
        if (types.isNotEmpty() && item.type !in types) return false
        if (favoritesOnly && !item.isFavorite) return false
        if (years.isNotEmpty() && item.year !in years) return false
        if (liveOnly && (item.isVideo || liveIds == null || item.id !in liveIds)) return false
        return true
    }

    /**
     * 单条件语义（顶部 chip 条）：任一条件独占，选择一种条件即清空其余；
     * 再次点选当前生效的条件则取消筛选。
     */
    fun selectType(type: MediaType): MediaFilter =
        if (types == setOf(type) && !favoritesOnly && !liveOnly && years.isEmpty()) {
            MediaFilter()
        } else {
            MediaFilter(types = setOf(type))
        }

    fun selectFavorite(): MediaFilter =
        if (favoritesOnly && types.isEmpty() && !liveOnly && years.isEmpty()) {
            MediaFilter()
        } else {
            MediaFilter(favoritesOnly = true)
        }

    fun selectLive(): MediaFilter =
        if (liveOnly && types.isEmpty() && !favoritesOnly && years.isEmpty()) {
            MediaFilter()
        } else {
            MediaFilter(liveOnly = true)
        }

    fun selectYear(year: Int): MediaFilter =
        if (years == setOf(year) && types.isEmpty() && !favoritesOnly && !liveOnly) {
            MediaFilter()
        } else {
            MediaFilter(years = setOf(year))
        }
}

/** 排序：最新在前 / 最早在前 */
enum class SortOrder { NEWEST, OLDEST }

/** 一条媒体记录（图片或视频） */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    /** 相对路径，如 DCIM/Camera */
    val relativePath: String,
    /** 绝对路径（可能为空，部分机型不提供） */
    val filePath: String?,
    val albumId: String,
    val albumName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** 拍摄/创建时间（毫秒），已做兜底回退 */
    val dateTakenMillis: Long,
    /** 文件修改时间（毫秒） */
    val dateModifiedMillis: Long,
    /** 视频时长（毫秒），图片为 0 */
    val durationMillis: Long,
    /** 存储方向（度） */
    val orientation: Int,
    val type: MediaType,
    val isFavorite: Boolean,
) {
    val isVideo: Boolean get() = type == MediaType.VIDEO

    /**
     * 拍摄日期（本地时区），构造时直接算一次并常驻：分组、按年筛选共用同一份结果，
     * 避免每次筛选/分组都对全表重复做 Instant → ZoneRules → LocalDate 转换。
     * 是类体字段而非构造参数，不参与 equals/hashCode/copy，数据类语义不变。
     */
    val localDate: LocalDate =
        Instant.ofEpochMilli(dateTakenMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    /** 拍摄年份（本地时区） */
    val year: Int get() = localDate.year
}

/** 相册（按文件夹归组） */
data class Album(
    val id: String,
    val name: String,
    val imageCount: Int,
    val videoCount: Int,
    val cover: MediaItem,
    /** 相册内按时间排序后的条目 */
    val items: List<MediaItem>,
) {
    val count: Int get() = imageCount + videoCount
}

/** 一个时间段分组（一天 / 一月 / 一年） */
data class MediaGroup(
    val key: String,
    val title: String,
    val items: List<MediaItem>,
) {
    val imageCount: Int get() = items.count { it.type == MediaType.IMAGE }
    val videoCount: Int get() = items.count { it.type == MediaType.VIDEO }
    val count: Int get() = items.size

    /** 头部副标题：全部是图片显示"N 张"，否则分类计数 */
    val subtitle: String
        get() = when {
            imageCount == count -> "$count 张"
            videoCount == count -> "$count 个"
            else -> "$imageCount 图 · $videoCount 视频"
        }
}
