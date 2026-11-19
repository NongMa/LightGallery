package com.liu.gallery.ui.info

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.liu.gallery.data.ExtraInfo
import com.liu.gallery.data.InfoLoader
import com.liu.gallery.data.LivePhotoLoader
import com.liu.gallery.data.MediaItem
import com.liu.gallery.util.formatBytesExact
import com.liu.gallery.util.formatDateTimeFull
import com.liu.gallery.util.formatDuration
import com.liu.gallery.util.formatMegapixels
import com.liu.gallery.util.formatSize

/**
 * 属性面板：文件属性 + 相机 EXIF / 视频元数据 + 整理操作
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    onToggleFavorite: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    canFavorite: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var extra by remember(item.id) { mutableStateOf<ExtraInfo?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isLive by produceState(initialValue = LivePhotoLoader.isLiveCached(item), key1 = item.id) {
        if (!item.isVideo) value = LivePhotoLoader.isLivePhoto(context, item)
    }

    LaunchedEffect(item.id, item.dateModifiedMillis) {
        extra = InfoLoader.load(context, item)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // 头部：图标 + 文件名 + 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (item.isVideo) Icons.Outlined.VideoLibrary
                        else Icons.Outlined.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            item.isVideo -> "视频属性"
                            isLive -> "实况照片 · 图片属性"
                            else -> "图片属性"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))

            // 属性行
            val rows = buildInfoRows(item, extra)
            rows.forEach { row ->
                InfoRow(label = row.first, value = row.second)
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

            // 操作区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoAction(
                    icon = if (item.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = if (item.isFavorite) "取消收藏" else "收藏",
                    enabled = canFavorite,
                    onClick = { onToggleFavorite(!item.isFavorite) },
                )
                InfoAction(
                    icon = Icons.Filled.Edit,
                    label = "重命名",
                    onClick = { showRename = true },
                )
                InfoAction(
                    icon = Icons.Filled.Delete,
                    label = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { showDeleteConfirm = true },
                )
            }
        }
    }

    // 重命名对话框
    if (showRename) {
        var nameInput by remember(item.id) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    label = { Text("文件名") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(nameInput)
                    showRename = false
                    onDismiss()
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("取消") }
            },
        )
    }

    // 删除确认
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除该项？") },
            text = {
                Text("「${item.name}」将被移入回收站，之后可在系统相册中恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDismiss()
                    onDelete()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InfoAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 组装属性行（label to value） */
private fun buildInfoRows(item: MediaItem, extra: ExtraInfo?): List<Pair<String, String>> =
    buildList {
        add("文件名" to item.name)
        add("类型" to buildString {
            append(item.mimeType)
            item.name.substringAfterLast('.', "").takeIf { it.length in 1..5 }
                ?.let { append("（.").append(it).append("）") }
        })
        if (item.width > 0 && item.height > 0) {
            add("分辨率" to "${item.width} × ${item.height} px")
            add("像素数" to formatMegapixels(item.width, item.height))
        }
        if (!item.isVideo) {
            add("方向" to "${item.orientation}°")
        }
        val realSize = if (item.sizeBytes > 0) item.sizeBytes else (extra?.fileSize ?: 0L)
        add("大小" to (if (realSize > 0)
            "${formatSize(realSize)}（${formatBytesExact(realSize)}）"
        else "未知"))
        if (item.isVideo && item.durationMillis > 0) {
            add("时长" to formatDuration(item.durationMillis))
        }
        add("拍摄时间" to formatDateTimeFull(item.dateTakenMillis))
        add("修改时间" to formatDateTimeFull(item.dateModifiedMillis))
        add("所在相册" to item.albumName)
        add("存储位置" to (item.relativePath.ifEmpty { "未知" }))
        add("收藏" to if (item.isFavorite) "是" else "否")
        add("媒体 ID" to item.id.toString())

        // 相机 / 元数据信息
        if (extra == null) {
            add("相机信息" to "读取中…")
        } else {
            extra.make?.takeIf { it.isNotBlank() }?.let { add("相机品牌" to it) }
            extra.model?.takeIf { it.isNotBlank() }?.let { add("相机型号" to it) }
            extra.lens?.takeIf { it.isNotBlank() }?.let { add("镜头" to it) }
            extra.fNumber?.takeIf { it.isNotBlank() }?.let { add("光圈" to it) }
            extra.exposure?.takeIf { it.isNotBlank() }?.let { add("快门" to it) }
            extra.iso?.takeIf { it.isNotBlank() }?.let { add("感光度" to "ISO $it") }
            extra.focalLength?.takeIf { it.isNotBlank() }?.let { add("焦距" to it) }
            extra.dateTimeOriginal?.takeIf { it.isNotBlank() }?.let {
                add("拍摄时间（EXIF）" to it)
            }
            extra.gps?.takeIf { it.isNotBlank() }?.let { add("GPS 坐标" to it) }
            extra.videoGps?.takeIf { it.isNotBlank() }?.let { add("GPS 坐标" to it) }
            extra.videoDate?.takeIf { it.isNotBlank() }?.let { add("封装时间" to it) }
            extra.software?.takeIf { it.isNotBlank() }?.let { add("软件" to it) }
            val hasAnyMeta = listOf(
                extra.make, extra.model, extra.lens, extra.fNumber, extra.exposure,
                extra.iso, extra.focalLength, extra.dateTimeOriginal, extra.gps,
                extra.videoGps, extra.videoDate, extra.software,
            ).any { !it.isNullOrBlank() }
            if (!hasAnyMeta) {
                add("相机信息" to if (item.isVideo) "无（未包含拍摄元数据）" else "无（未包含 EXIF 数据）")
            }
        }
    }
