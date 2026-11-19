package com.liu.gallery.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import my.nanihadesuka.compose.LazyVerticalGridScrollbar
import my.nanihadesuka.compose.ScrollbarLayoutSide
import my.nanihadesuka.compose.ScrollbarSelectionActionable
import my.nanihadesuka.compose.ScrollbarSelectionMode
import my.nanihadesuka.compose.ScrollbarSettings
import com.liu.gallery.data.LivePhotoLoader
import com.liu.gallery.data.MediaGroup
import com.liu.gallery.data.MediaItem
import com.liu.gallery.data.VideoThumbLoader
import com.liu.gallery.util.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val CONTENT_HEADER = "header"
private const val CONTENT_MEDIA = "media"

/** A/B 测量开关：临时关闭预取 */
private const val PRELOAD_ENABLED = false

/** 恒定缩略图解码尺寸：不随列数/模式变化，这样切日/月/年或捏合档位时，
 * Coil / VideoThumbLoader 的缓存键不变，缩略图命中内存缓存直接显示，避免闪烁。 */
private const val THUMB_LOAD_PX = 320

// ------------------------------------------------------------------ 缩略图

/**
 * 图片走图片库（内存/磁盘缓存），视频走系统取帧 + LRU 缓存。
 * 解码尺寸恒定为 THUMB_LOAD_PX，不随列数/模式变化，否则切日/月/年或捏合档位时
 * 缓存键会改变，所有可见缩略图都要重新解码 → 一闪一闪；小格子直接按 ContentScale.Crop 缩放显示。
 * 快速 scrub（右边缘就位条拖动）期间不发解码请求，只画纯色块占位保证绝对跟手，松手后恢复原图。
 */
@Composable
fun MediaThumb(item: MediaItem, modifier: Modifier = Modifier, scrubbing: Boolean = false) {
    if (scrubbing) {
        // scrub 期间零解码：纯色块占位
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    if (item.isVideo) {
        VideoThumb(item, modifier, scrubbing = false)
    } else {
        // 与预取请求完全同参（size/scale/precision、同一 SingletonImageLoader），
        // 滚动时直接命中内存缓存；remember 防止重组重建请求导致加载重启。
        // 不做淡入（对齐 MIUI 秒显示），省掉淡入动画期间的持续重绘。
        val context = LocalContext.current
        val request = remember(item.uri) {
            ImageRequest.Builder(context)
                .data(item.uri)
                .size(THUMB_LOAD_PX, THUMB_LOAD_PX)
                .scale(Scale.FILL)
                .precision(Precision.INEXACT)
                .crossfade(100)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun VideoThumb(item: MediaItem, modifier: Modifier = Modifier, scrubbing: Boolean = false) {
    val context = LocalContext.current
    // scrub 期间不取视频帧（取帧太重），显示占位播放标；松手后自动取帧
    if (scrubbing) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(26.dp),
            )
        }
        return
    }
    val thumb by produceState<Bitmap?>(
        initialValue = null,
        key1 = item.id,
        key2 = item.dateModifiedMillis,
    ) {
        value = VideoThumbLoader.load(context, item, THUMB_LOAD_PX)
    }
    val bitmap = thumb
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

// -------------------------------------------------------------------- 网格项

@Composable
fun MediaCell(
    item: MediaItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    aspect: Float = 1f,
    /** 是否显示提示徽标（视频播放标/时长、实况）——仅“按日”大单元格显示 */
    showHints: Boolean = true,
    /** 直角单元格（按年 16 列小图：不做圆角裁切） */
    square: Boolean = false,
    /** 正在被右边缘就位条快速 scrub：缩略图极小解码、隐藏提示/角标，跟手优先 */
    scrubbing: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current

    // iOS 式按压缩放反馈（手动跟踪按下态）
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "cellPress",
    )
    val selectScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "cellSelect",
    )

    // 实况照片：仅对 JPEG 异步检测一次并记忆（提示徽标不显示时不做文件检测，省 I/O）
    val context = LocalContext.current
    val isLive by produceState(initialValue = showHints && !scrubbing && LivePhotoLoader.isLiveCached(item), key1 = item.id, key2 = showHints, key3 = scrubbing) {
        if (showHints && !scrubbing && !item.isVideo) value = LivePhotoLoader.isLivePhoto(context, item)
    }

    // 单元格裁切形状：按年 16 列小图直角，否则 8dp 圆角
    val cellShape = if (square) RectangleShape else RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(cellShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(item.id) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
            },
    ) {
        MediaThumb(item = item, modifier = Modifier.fillMaxSize(), scrubbing = scrubbing)

        // 视频：居中播放标 + 时长角标（仅按日显示；scrub 时隐藏，减少重绘）
        if (showHints && !scrubbing && item.isVideo && !selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (item.durationMillis > 0) {
                DurationBadge(formatDuration(item.durationMillis))
            }
        }

        // 实况角标（左下，仅按日显示；scrub 时隐藏）
        if (showHints && !scrubbing && isLive && !item.isVideo && !selected) {
            LiveBadge()
        }

        // 收藏角标（仅按日显示，提示信息一并隐藏；scrub 时隐藏）
        if (showHints && !scrubbing && item.isFavorite && !selected) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "已收藏",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(15.dp)
                    .shadow(3.dp, CircleShape),
            )
        }

        // 选中遮罩与对勾（弹簧弹入）
        if (selectScale > 0.01f) {
            // 深色压暗 + 主色内描边，白勾压在暗底上才看得清
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.32f * selectScale)),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = selectScale),
                        shape = cellShape,
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = selectScale
                        scaleY = selectScale
                    }
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = if (selected) "已选中" else null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** 实况照片角标：双圆环 + “实况”胶囊 */
@Composable
private fun BoxScope.LiveBadge() {
    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(5.dp)
            .shadow(3.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier.size(11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .border(1.5.dp, Color.White, CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(4.5.dp)
                    .background(Color.White, CircleShape),
            )
        }
        Text(
            text = "实况",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BoxScope.DurationBadge(text: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp),
        )
    }
}

// ------------------------------------------------------------- 分区头与网格

@Composable
fun SectionHeader(group: MediaGroup, big: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.title,
            style = if (big) MaterialTheme.typography.titleLarge
            else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = group.subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 方向感知预取：复刻 MIUI 系统相册接的 Glide ListPreloader 规则——
 * 首个可见项变化时判方向，方向反转先取消在途预取（cancelAll）；
 * 沿滚动方向从上次边界（lastStart/lastEnd）增量预取
 * “可见区之后 24 条”（Config$ThumbConfig.sPreloadNum，上限防重复）。
 *
 * 图片走 SingletonImageLoader.execute 按格子像素写内存缓存
 * （与显示请求同参，下一帧显示直接命中）；视频进帧 LRU；
 * JPEG 顺带预热实况检测（只读文件头几 KB，角标首帧即出）。
 * 每次最多 2 批并行（环形挤掉最旧批次），不与前台显示请求抢解码槽。
 */
@Composable
fun MediaPreloadEffect(
    gridState: LazyGridState,
    items: List<MediaItem>,
    prewarmLive: Boolean = true,
) {
    val context = LocalContext.current

    LaunchedEffect(gridState, items, prewarmLive) {
        val total = items.size
        if (total == 0) return@LaunchedEffect

        // 条目 → 平铺下标（网格显示顺序；分区头不在此列）
        val indexById = HashMap<Long, Int>(total * 2 + 1).apply {
            items.forEachIndexed { index, item -> put(item.id, index) }
        }
        val maxPreload = 24
        val loader = SingletonImageLoader.get(context)

        suspend fun preloadOne(item: MediaItem) {
            if (item.isVideo) {
                VideoThumbLoader.load(context, item, THUMB_LOAD_PX)
            } else {
                // 图片先行（滚动关键路径），成功即写内存缓存
                loader.execute(
                    ImageRequest.Builder(context)
                        .data(item.uri)
                        .size(THUMB_LOAD_PX, THUMB_LOAD_PX)
                        .scale(Scale.FILL)
                        .precision(Precision.INEXACT)
                        .build()
                )
                // 再预热实况检测（提示徽标不显示时跳过，省 I/O）
                if (prewarmLive) LivePhotoLoader.isLivePhoto(context, item)
            }
        }

        // 在途预取批次：环形最多 2 批并行，超限挤掉最旧批次
        val inFlight = ArrayDeque<Job>()
        var lastFirst = -1 // 上次首个可见项的网格下标（判方向）
        var lastStart = 0
        var lastEnd = 0
        var isIncreasing = true

        // 三元组结构相等时不发射：只在首个项/末个可见项真正变化时才动作
        snapshotFlow {
            val visible = gridState.layoutInfo.visibleItemsInfo
            val gridFirst = visible.firstOrNull()?.index ?: -1
            val firstKey = visible.firstOrNull { it.key is Long }?.key
            val lastKey = visible.lastOrNull { it.key is Long }?.key
            Triple(gridFirst, firstKey, lastKey)
        }.collect { (gridFirst, firstKey, lastKey) ->
            if (gridFirst < 0) return@collect

            // ListPreloader.onScroll：首个可见项变化判方向，反转先取消在途
            val increasing = when {
                lastFirst < 0 -> true
                gridFirst > lastFirst -> true
                gridFirst < lastFirst -> false
                else -> isIncreasing
            }
            lastFirst = gridFirst
            if (isIncreasing != increasing) {
                isIncreasing = increasing
                inFlight.forEach { it.cancel() }
                inFlight.clear()
            }

            val first = (firstKey as? Long)?.let(indexById::get) ?: return@collect
            val last = (lastKey as? Long)?.let(indexById::get) ?: return@collect

            // ListPreloader.preload(i, i2)：lastStart/lastEnd 记边界防重复
            val start: Int
            val end: Int
            if (increasing) {
                start = maxOf(lastEnd, last + 1)
                end = minOf(total, last + 1 + maxPreload)
            } else {
                start = maxOf(0, first - maxPreload)
                end = minOf(lastStart, first)
            }
            lastStart = start
            lastEnd = end
            if (start >= end) return@collect

            val forward = increasing
            val job = launch {
                if (forward) {
                    for (idx in start until end) preloadOne(items[idx])
                } else {
                    for (idx in end - 1 downTo start) preloadOne(items[idx])
                }
            }
            inFlight.addLast(job)
            inFlight.removeAll { !it.isActive }
            while (inFlight.size > 2) inFlight.removeFirst().cancel()
        }
    }
}

/**
 * 分组网格：整行分区头 + 滚动悬浮日期胶囊。
 * 头部滚出视口后胶囊淡入，随时提示所处时间段。
 */
@Composable
fun GroupedMediaGrid(
    groups: List<MediaGroup>,
    selected: Set<Long>,
    selectionMode: Boolean,
    onCellClick: (MediaItem) -> Unit,
    onCellLongClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    bigHeaders: Boolean = false,
    /** 是否显示单元格提示徽标（视频播放标/时长、实况）——仅“按日”大单元格显示 */
    showHints: Boolean = true,
    /** 直角单元格（按年 16 列小图：不做圆角裁切） */
    square: Boolean = false,
    /** 正在被右边缘就位条快速 scrub：关掉单元格位移动画，避免瞬跳时整屏跟不上手 */
    scrubbing: Boolean = false,
    /** 回调当前库拇指是否处于拖动按压状态（驱动就位条“短条/完整”两种形态） */
    onThumbSelectedChange: ((Boolean) -> Unit)? = null,
    columns: androidx.compose.foundation.lazy.grid.GridCells = GridCells.Adaptive(minSize = 104.dp),
    contentPadding: PaddingValues = PaddingValues(3.dp),
) {
    // 条目 id → 分组标题（分组变化时重建一次）
    val titleById = remember(groups) {
        HashMap<Long, String>(groups.sumOf { it.count } * 2 + 1).apply {
            groups.forEach { g -> g.items.forEach { put(it.id, g.title) } }
        }
    }
    // 每组起始扁平下标（含分区头）：就位条气泡用，O(logN) 二分查找
    val flatStarts = remember(groups) {
        val starts = IntArray(groups.size)
        var idx = 0
        for (i in groups.indices) {
            starts[i] = idx
            idx += 1 + groups[i].items.size
        }
        starts
    }
    fun titleAt(flatIndex: Int): String {
        if (flatStarts.isEmpty()) return ""
        var lo = 0
        var hi = flatStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (flatStarts[mid] <= flatIndex) lo = mid else hi = mid - 1
        }
        return groups.getOrNull(lo)?.title.orEmpty()
    }
    var pillTitle by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(gridState, titleById) {
        snapshotFlow {
            val visible = gridState.layoutInfo.visibleItemsInfo
            val headerOnScreen = visible.any { it.contentType == CONTENT_HEADER }
            val firstMedia = visible.firstOrNull { it.contentType == CONTENT_MEDIA }
            val title = (firstMedia?.key as? Long)?.let { titleById[it] }
            val scrolled = (visible.firstOrNull()?.index ?: 0) > 0
            if (!headerOnScreen && scrolled) title else null
        }.collectLatest { pillTitle = it }
    }

    // 预取序列 = 网格内媒体的平铺顺序（与显示顺序一致）
    val flatMedia = remember(groups) { groups.flatMap { it.items } }
    // MIUI 同规则方向感知预取：图片入内存缓存 / 视频帧入 LRU / 实况检测预热
    // A/B: 临时关闭预取测量
    if (PRELOAD_ENABLED) MediaPreloadEffect(gridState = gridState, items = flatMedia, prewarmLive = showHints)

    // 直角单元格（按年 16 列小图）：保留 1px 细线分隔（按像素换算）
    val hairline = (1f / LocalDensity.current.density).dp
    val itemSpacing = if (square) hairline else 3.dp
    // 内容铺满底部屏幕边缘（标签栏悬浮在上方采样），末行通过底部内边距让位
    // 标签栏 62dp + 底间距 8dp + 手势导航条 ≈ 88dp
    val bottomClearance = 88.dp
    val gridPadding = if (square) {
        PaddingValues(start = hairline, top = hairline, end = hairline, bottom = bottomClearance)
    } else {
        // 调用方均使用默认 3dp contentPadding；底部统一改为 88dp 清场
        PaddingValues(start = 3.dp, top = 3.dp, end = 3.dp, bottom = bottomClearance)
    }


    Box(modifier = modifier) {
        // 右边缘就位条：用成熟库接管拖动（Full 模式：按下跳转+跟手拖动；Always：隐藏时也可摸）
        LazyVerticalGridScrollbar(
            state = gridState,
            settings = ScrollbarSettings(
                enabled = true,
                side = ScrollbarLayoutSide.End,
                alwaysShowScrollbar = false,
                // 库拇指画成透明，只当作“可拖感应区”（命中区=2×padding+厚度=36dp，与覆盖层白色轨道等宽）
                // 视觉轨道/圆钮由 TimelineScrubBar 覆盖层按旧样式绘制
                scrollbarPadding = 3.dp,
                thumbThickness = 30.dp,
                thumbShape = RoundedCornerShape(50),
                thumbMinLength = 0.10f,
                thumbMaxLength = 1f,
                thumbUnselectedColor = Color.Transparent,
                thumbSelectedColor = Color.Transparent,
                selectionMode = ScrollbarSelectionMode.Full,
                selectionActionable = ScrollbarSelectionActionable.Always,
                hideDelayMillis = 1500,
                hideDisplacement = 20.dp,
                durationAnimationMillis = 260,
            ),
            indicatorContent = { index, isThumbSelected ->
                // isThumbSelected 只在真实按压拖动时为 true：向上透出给就位条做“短条/完整”切换
                androidx.compose.runtime.LaunchedEffect(isThumbSelected) {
                    onThumbSelectedChange?.invoke(isThumbSelected)
                }
                // 拖动期间气泡显示落点所在分组标题（库负责定位，放在滚轮旁边）
                if (isThumbSelected) {
                    Text(
                        text = titleAt(index),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xED232323))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            },
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = columns,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                verticalArrangement = Arrangement.spacedBy(itemSpacing),
                contentPadding = gridPadding,
            ) {
            groups.forEach { group ->
                item(
                    key = "hdr_${group.key}",
                    contentType = CONTENT_HEADER,
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    // 换档（捏合/点日月年）时让分区头和单元格滑动到新位置，而不是瞬跳
                    SectionHeader(
                        group = group,
                        big = bigHeaders,
                        modifier = if (scrubbing) Modifier else Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                        ),
                    )
                }
                items(
                    items = group.items,
                    key = { it.id },
                    contentType = { CONTENT_MEDIA },
                ) { item ->
                    MediaCell(
                        item = item,
                        selected = selectionMode && item.id in selected,
                        onClick = { onCellClick(item) },
                        onLongClick = { onCellLongClick(item) },
                        showHints = showHints,
                        square = square,
                        scrubbing = scrubbing,
                        modifier = if (scrubbing) Modifier else Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                        ),
                    )
                }
            }
            }
        }

        // 悬浮日期胶囊
        AnimatedVisibility(
            visible = pillTitle != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(50),
                shadowElevation = 2.dp,
            ) {
                Text(
                    text = pillTitle ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// --------------------------------------------------------------- 空态/授权

@Composable
fun EmptyContent(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickableNoRipple(onAction),
            ) {
                Text(
                    text = actionText,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** 无水波纹点击 */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
            indication = null,
            onClick = onClick,
        )
    )
