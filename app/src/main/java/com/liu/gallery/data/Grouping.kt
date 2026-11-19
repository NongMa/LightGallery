package com.liu.gallery.data

import java.time.LocalDate
import java.time.ZoneId

private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 按 日 / 月 / 年 对已排序的条目分组。
 * 返回顺序与入参一致（列表已按时间排序）。
 */
fun groupByDate(items: List<MediaItem>, mode: GroupMode): List<MediaGroup> {
    if (items.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)

    // 先按 key 归并，同时缓存每组的代表日期，避免二次解析
    val buckets = LinkedHashMap<String, MutableList<MediaItem>>(items.size / 8 + 1)
    val dateByKey = HashMap<String, LocalDate>(items.size / 8 + 1)
    for (item in items) {
        val date = item.localDate
        val key = when (mode) {
            GroupMode.DAY -> date.toString()                      // 2026-11-06
            GroupMode.MONTH -> "${date.year}-${date.monthValue}"  // 2026-11
            GroupMode.YEAR -> date.year.toString()                // 2026
        }
        dateByKey.putIfAbsent(key, date)
        buckets.getOrPut(key) { ArrayList() }.add(item)
    }

    return buckets.map { (key, list) ->
        val date = dateByKey[key] ?: today
        MediaGroup(key = key, title = formatGroupTitle(mode, date, today), items = list)
    }
}

/** 分组标题：今天 / 昨天 / 11月6日 周五 / 2025年11月6日 / 11月 / 2026年 */
fun formatGroupTitle(mode: GroupMode, date: LocalDate, today: LocalDate): String = when (mode) {
    GroupMode.DAY -> when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> if (date.year == today.year) {
            "${date.monthValue}月${date.dayOfMonth}日 ${WEEKDAYS[date.dayOfWeek.value - 1]}"
        } else {
            "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
    }

    GroupMode.MONTH ->
        if (date.year == today.year) "${date.monthValue}月"
        else "${date.year}年${date.monthValue}月"

    GroupMode.YEAR -> "${date.year}年"
}
