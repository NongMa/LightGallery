package com.liu.gallery.util

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 文件大小：12.5 MB（附精确字节数） */
fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.CHINA, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/** 精确字节数，带千分位 */
fun formatBytesExact(bytes: Long): String = String.format(Locale.CHINA, "%,d 字节", bytes)

/** 时长：03:25 / 1:02:03 */
fun formatDuration(millis: Long): String {
    if (millis <= 0) return "00:00"
    val totalSec = millis / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.CHINA, "%02d:%02d", m, s)
}

/** 日期时间：2026-11-06 14:23 */
fun formatDateTime(millis: Long): String {
    if (millis <= 0) return "—"
    val zdt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return String.format(
        Locale.CHINA, "%04d-%02d-%02d %02d:%02d",
        zdt.year, zdt.monthValue, zdt.dayOfMonth, zdt.hour, zdt.minute
    )
}

/** 日期时间（含秒）：2026年11月6日 14:23:05 */
fun formatDateTimeFull(millis: Long): String {
    if (millis <= 0) return "—"
    val zdt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return String.format(
        Locale.CHINA, "%d年%d月%d日 %02d:%02d:%02d",
        zdt.year, zdt.monthValue, zdt.dayOfMonth, zdt.hour, zdt.minute, zdt.second
    )
}

/** 千分位计数 */
fun formatCount(count: Int): String = String.format(Locale.CHINA, "%,d", count)

/** 像素数：4000×3000 → 1200 万像素 */
fun formatMegapixels(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "—"
    val mp = width.toLong() * height
    return when {
        mp >= 100_000_000 -> String.format(Locale.CHINA, "%.1f 亿像素", mp / 1e8)
        mp >= 10_000_000 -> String.format(Locale.CHINA, "%.0f 万像素", mp / 1e4)
        mp >= 1_000_000 -> String.format(Locale.CHINA, "%.1f 万像素", mp / 1e4)
        else -> String.format(Locale.CHINA, "%,d 像素", mp)
    }
}
