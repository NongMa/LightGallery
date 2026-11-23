package com.liu.gallery.ui.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import com.liu.gallery.R
import com.liu.gallery.data.GroupMode
import com.liu.gallery.data.LivePhotoLoader
import com.liu.gallery.data.VideoThumbLoader
import com.liu.gallery.vm.AlbumViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页：常规（默认时间线视图）、存储（清理缓存）、关于（图标/版本/开源许可）。
 * 由时光页右上角齿轮进入；无标签栏，左上角返回。
 */
@Composable
fun SettingsScreen(
    vm: AlbumViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val groupMode by vm.groupMode.collectAsStateWithLifecycle()

    var cacheBytes by remember { mutableLongStateOf(-1L) }
    var clearing by remember { mutableStateOf(false) }
    var groupDialog by remember { mutableStateOf(false) }
    var licenseDialog by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { dirSize(context.cacheDir) } }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
    ) {
        // 标题行：返回钮 + 大标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack = onBack)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "设置",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        ) {
            // ---------------- 常规 ----------------
            SectionLabel("常规")
            SettingsCard {
                SettingsRow(
                    title = "默认时间线视图",
                    value = when (groupMode) {
                        GroupMode.DAY -> "日"
                        GroupMode.MONTH -> "月"
                        GroupMode.YEAR -> "年"
                    },
                    onClick = { groupDialog = true },
                )
            }

            // ---------------- 存储 ----------------
            SectionLabel("存储")
            SettingsCard {
                SettingsRow(
                    title = "清理缓存",
                    value = when {
                        clearing -> "清理中…"
                        cacheBytes < 0 -> "计算中…"
                        cacheBytes == 0L -> "无缓存"
                        else -> formatBytes(cacheBytes)
                    },
                    enabled = !clearing,
                    onClick = {
                        clearing = true
                        val freed = cacheBytes.coerceAtLeast(0L)
                        scope.launch(Dispatchers.IO) {
                            clearAllCache(context)
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                VideoThumbLoader.clear()
                                LivePhotoLoader.clearInMemory()
                                cacheBytes = 0L
                                clearing = false
                                android.widget.Toast.makeText(
                                    context,
                                    "缓存已清理（释放 ${formatBytes(freed)}）",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }

            // ---------------- 关于 ----------------
            SectionLabel("关于")
            SettingsCard {
                AboutRow()
                RowDivider()
                SettingsRow(
                    title = "数据来源",
                    value = "仅本机",
                )
                RowDivider()
                SettingsRow(
                    title = "开源许可",
                    onClick = { licenseDialog = true },
                )
            }
        }
    }

    // ---------------- 默认时间线视图 ----------------
    if (groupDialog) {
        AlertDialog(
            onDismissRequest = { groupDialog = false },
            title = { Text("默认时间线视图") },
            text = {
                Column {
                    listOf(
                        GroupMode.DAY to "日",
                        GroupMode.MONTH to "月",
                        GroupMode.YEAR to "年",
                    ).forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    vm.setDefaultGroupMode(mode)
                                    groupDialog = false
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = groupMode == mode,
                                onClick = {
                                    vm.setDefaultGroupMode(mode)
                                    groupDialog = false
                                },
                            )
                            Text(text = label, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { groupDialog = false }) { Text("完成") }
            },
        )
    }

    // ---------------- 开源许可 ----------------
    if (licenseDialog) {
        AlertDialog(
            onDismissRequest = { licenseDialog = false },
            title = { Text("开源许可") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LicenseEntry("LazyColumnScrollbar 2.2.0", "MIT License · Copyright © nanihadesuka")
                    LicenseEntry("Compose / Material3 / Navigation", "Apache License 2.0 · Google")
                    LicenseEntry("Coil 3", "Apache License 2.0 · Instacart")
                    LicenseEntry("Backdrop（液态玻璃）", "MIT License · kyant0")
                }
            },
            confirmButton = {
                TextButton(onClick = { licenseDialog = false }) { Text("完成") }
            },
        )
    }
}

// ------------------------------------------------------------------ 子部件

@Composable
private fun BackButton(onBack: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onBack()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ArrowBack,
            contentDescription = "返回",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
    ) {
        content()
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** 设置行：左标题，右值（灰）；可点击时整行响应 */
@Composable
private fun SettingsRow(
    title: String,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 关于：应用图标 + 名称 + 版本 */
@Composable
private fun AboutRow() {
    val context = LocalContext.current
    val pkgInfo = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = pkgInfo?.versionName ?: "1.0.0"
    val versionCode = if (Build.VERSION.SDK_INT >= 28) {
        pkgInfo?.longVersionCode ?: 1L
    } else {
        @Suppress("DEPRECATION")
        pkgInfo?.versionCode?.toLong() ?: 1L
    }

    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 复用自绘图标（深藏青底 + 彩色花瓣），无需额外资源
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF1B1B2F)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = "清影相册",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "版本 $versionName ($versionCode)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun LicenseEntry(name: String, license: String) {
    Column {
        Text(
            text = name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = license,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

// ------------------------------------------------------------------ 缓存工具

private fun dirSize(dir: File): Long {
    var total = 0L
    if (!dir.exists()) return 0L
    dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
    return total
}

/** 全删缓存目录下的内容（都是可重建的派生缓存）；保留 SingletonImageLoader 自身注册 */
private fun clearAllCache(context: Context) {
    val dir = context.cacheDir
    if (dir.exists()) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
    // Coil 内存缓存（磁盘缓存也随 cacheDir 一并清空）
    SingletonImageLoader.get(context).memoryCache?.clear()
    SingletonImageLoader.get(context).diskCache?.clear()
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
