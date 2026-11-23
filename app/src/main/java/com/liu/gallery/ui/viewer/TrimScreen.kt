package com.liu.gallery.ui.viewer

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.liu.gallery.data.ImageFilter
import com.liu.gallery.data.MediaItem
import com.liu.gallery.data.applyImageEdit
import com.liu.gallery.data.decodeEditableImage
import com.liu.gallery.ui.components.MediaThumb
import com.liu.gallery.ui.components.clickableNoRipple
import com.liu.gallery.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 预览位图最长边：与落盘分辨率脱钩，仅保证所见即所得的显示效果
private const val PREVIEW_MAX_DIM = 1400

/**
 * 剪辑界面（全屏覆盖层）。
 *
 * - 图片：滤镜 chips + 旋转按钮 + 所见即所得预览 + 另存为新文件；
 * - 视频：时间范围滑块 + 时长/范围标签 + 无损裁剪另存为新文件（不重编码）。
 *
 * 结果一律另存为 MediaStore 新条目，原文件保持不动；导出中禁用所有操作并显示遮罩。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(
    item: MediaItem,
    busy: Boolean,
    onImageExport: (filter: ImageFilter?, rotationDeg: Int) -> Unit,
    onVideoExport: (startMs: Long, endMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // 图片滤镜与旋转角度
    var filter by remember(item.id) { mutableStateOf<ImageFilter?>(null) }
    var rotationDeg by remember(item.id) { mutableStateOf(0) }

    // 视频裁剪范围（毫秒）
    val duration = item.durationMillis
    var startMs by remember(item.id) { mutableFloatStateOf(0f) }
    var endMs by remember(item.id) { mutableFloatStateOf(if (duration > 0) duration.toFloat() else 0f) }

    // 跟随系统深浅色：状态栏/导航栏图标颜色与背景明暗相反
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    DisposableEffect(systemDark) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !systemDark
        controller?.isAppearanceLightNavigationBars = !systemDark
        onDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 顶部标题栏（避开状态栏，保证关闭按钮可点）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = if (item.isVideo) "视频裁剪" else "图片编辑",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            // ---- 预览区（占剩余空间）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (item.isVideo) {
                    MediaThumb(item = item, modifier = Modifier.fillMaxSize())
                } else {
                    ImageEditPreview(
                        item = item,
                        filter = filter,
                        rotationDeg = rotationDeg,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ---- 操作区（底部固定）
            if (item.isVideo) {
                VideoTrimControls(
                    durationMs = duration,
                    startMs = startMs,
                    endMs = endMs,
                    busy = busy,
                    onRangeChange = { s, e -> startMs = s; endMs = e },
                    onExport = {
                        onVideoExport(startMs.toLong(), endMs.toLong())
                    },
                )
            } else {
                ImageEditControls(
                    filter = filter,
                    rotationDeg = rotationDeg,
                    busy = busy,
                    onFilterChange = { filter = it },
                    onRotate = { rotationDeg = (rotationDeg + 90) % 360 },
                    onReset = { filter = null; rotationDeg = 0 },
                    onExport = { onImageExport(filter, rotationDeg) },
                )
            }
        }

        // ---- 导出中遮罩
        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// --------------------------------------------------------------- 图片预览

/** 应用滤镜 + 旋转的实时预览（后台解码 + 变换，缓存到 State 中） */
@Composable
private fun ImageEditPreview(
    item: MediaItem,
    filter: ImageFilter?,
    rotationDeg: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preview by produceState<Bitmap?>(
        initialValue = null,
        key1 = item.id,
        key2 = filter,
        key3 = rotationDeg,
    ) {
        value = withContext(Dispatchers.Default) {
            val src = decodeEditableImage(context, item.uri, PREVIEW_MAX_DIM)
                ?: return@withContext null
            applyImageEdit(src, filter, rotationDeg)
        }
    }
    val bitmap = preview
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                text = "解码失败",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    }
}

// --------------------------------------------------------------- 图片操作区

@Composable
private fun ImageEditControls(
    filter: ImageFilter?,
    rotationDeg: Int,
    busy: Boolean,
    onFilterChange: (ImageFilter?) -> Unit,
    onRotate: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 滤镜 chips（含"原图"）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChipItem(
                label = "原图",
                selected = filter == null,
                onClick = { onFilterChange(null) },
            )
            ImageFilter.entries.forEach { f ->
                FilterChipItem(
                    label = f.label,
                    selected = filter == f,
                    onClick = { onFilterChange(f) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // 旋转 + 重置 + 导出
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(
                icon = Icons.Outlined.Rotate90DegreesCw,
                label = "旋转",
                enabled = !busy,
                onClick = onRotate,
            )
            Spacer(Modifier.width(8.dp))
            ControlButton(
                icon = Icons.Outlined.RestartAlt,
                label = "重置",
                enabled = !busy,
                onClick = onReset,
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onExport,
                enabled = !busy,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = "另存为",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 13.sp,
                maxLines = 1,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = null,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .then(if (enabled) Modifier.clickableNoRipple(onClick = onClick) else Modifier)
            .padding(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

// --------------------------------------------------------------- 视频操作区

@Composable
private fun VideoTrimControls(
    durationMs: Long,
    startMs: Float,
    endMs: Float,
    busy: Boolean,
    onRangeChange: (start: Float, end: Float) -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 时长范围标签
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDuration(startMs.toLong()),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "裁剪 ${formatDuration((endMs - startMs).toLong())}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatDuration(endMs.toLong()),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))

        // 时间范围滑块
        if (durationMs > 0) {
            RangeSlider(
                value = startMs..endMs,
                onValueChange = { range ->
                    if (!busy) onRangeChange(range.start, range.endInclusive)
                },
                valueRange = 0f..durationMs.toFloat(),
                enabled = !busy,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            // 重置全范围
            ControlButton(
                icon = Icons.Outlined.RestartAlt,
                label = "重置",
                enabled = !busy,
                onClick = {
                    onRangeChange(0f, durationMs.toFloat())
                },
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onExport,
                enabled = !busy && (endMs - startMs > 100f),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = "另存为",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}
