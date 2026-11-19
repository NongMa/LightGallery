package com.liu.gallery.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ln
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liu.gallery.data.GroupMode
import com.liu.gallery.data.MediaGroup
import com.liu.gallery.data.MediaFilter
import com.liu.gallery.data.MediaItem
import com.liu.gallery.data.MediaType
import com.liu.gallery.data.SortOrder
import com.liu.gallery.ui.components.EmptyContent
import com.liu.gallery.ui.components.GroupedMediaGrid
import com.liu.gallery.ui.components.SelectionBar
import com.liu.gallery.ui.filter.FilterChipBar
import com.liu.gallery.ui.glass.GlassIconButton
import com.liu.gallery.ui.glass.Springs
import com.liu.gallery.ui.glass.liquidGlass
import com.liu.gallery.ui.glass.rememberGlassTint
import com.liu.gallery.util.formatCount
import com.liu.gallery.vm.AlbumViewModel

/**
 * 捏合档位（MIUI 相册同规则，对应 PictureViewMode 链）：
 * 一次捏合沿档位表走一档，列数与日/月/年分组一起切换。
 * 列数取自 MIUI w321dp 手机资源 thumb_large/micro/mini/tiny_columns。
 */
private class ZoomStep(val mode: GroupMode, val columns: Int)

private val ZOOM_STEPS = listOf(
    ZoomStep(GroupMode.DAY, 4),
    ZoomStep(GroupMode.DAY, 5),
    ZoomStep(GroupMode.MONTH, 8),
    ZoomStep(GroupMode.YEAR, 16),
)

/** 分组平铺（每组 1 个头 + N 个单元格）中的扁平索引 */
private fun flatGridIndex(groups: List<MediaGroup>, id: Long): Int {
    var idx = 0
    for (g in groups) {
        val at = g.items.indexOfFirst { it.id == id }
        if (at >= 0) return idx + 1 + at
        idx += 1 + g.items.size
    }
    return -1
}

/**
 * 时间线页（iOS 27 相册风格）：
 * - 大标题 + 悬浮玻璃工具条（日/月/年 + 全部/图片/视频/收藏 + 排序，单行玻璃胶囊）
 * - 网格 Fixed(3) 紧凑间距
 * - 多选时切换为玻璃多选条
 * - 局部 layerBackdrop：工具条对下方内容做真实折射采样
 */
@Composable
fun TimelineScreen(
    vm: AlbumViewModel,
    contentPadding: PaddingValues,
    onOpenViewer: (List<MediaItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    canFavorite: Boolean = true,
    onOpenSettings: () -> Unit = {},
) {
    val groups by vm.groups.collectAsState()
    val filtered by vm.filtered.collectAsState()
    val loading by vm.loading.collectAsState()
    val selection by vm.selection.collectAsState()
    val groupMode by vm.groupMode.collectAsState()
    val filter by vm.filter.collectAsState()
    val sort by vm.sort.collectAsState()
    val liveScanning by vm.liveScanning.collectAsState()

    // 筛选 chip 条开合状态（Tune 钮切换）
    var filterOpen by remember { androidx.compose.runtime.mutableStateOf(false) }

    // 捏合档位下标（MIUI 同规则：一次捏合沿档位链走一档，列数与日/月/年联动）
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val columns = ZOOM_STEPS[stepIndex].columns

    val selectionMode = selection.isNotEmpty()
    // 工具条手动切日/月/年时，档位同步到该模式的默认列数
    androidx.compose.runtime.LaunchedEffect(groupMode) {
        if (ZOOM_STEPS.getOrNull(stepIndex)?.mode != groupMode) {
            val idx = ZOOM_STEPS.indexOfFirst { it.mode == groupMode }
            if (idx >= 0) stepIndex = idx
        }
    }
    val selectedItems = remember(filtered, selection) {
        filtered.filter { it.id in selection }
    }
    val gridState = rememberLazyGridState()
    // 快速位置跳变检测：相邻两次 emission 下标差 ≥ 6 认为是就位条 scrub（或大幅瞬跳），
    // 期间单元格走纯色块占位 / 就位条刻度常显；停止 300ms 后恢复常规渲染
    var rapidScrolling by remember { mutableStateOf(false) }
    var jumpSerial by remember { mutableIntStateOf(0) }
    // 库拇指是否正被手指按压拖动（慢速拖动也一定为 true）
    var thumbSelected by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        var lastIndex = gridState.firstVisibleItemIndex
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { idx ->
            val d = kotlin.math.abs(idx - lastIndex)
            lastIndex = idx
            if (d >= 6) {
                jumpSerial += 1 // 触发看门狗重计时
                rapidScrolling = true
            }
        }
    }
    LaunchedEffect(jumpSerial) {
        if (jumpSerial == 0) return@LaunchedEffect
        delay(300) // 最后一次跳变 300ms 后清除标记
        rapidScrolling = false
    }

    // 系统相册式逐级钻取：非日视图点击 → 切更细分组（列数随之减少）并定位到该格外效果
    var pendingLocate by remember { mutableStateOf<Pair<Long, GroupMode>?>(null) }
    var groupsAtRequest by remember { mutableStateOf<List<MediaGroup>?>(null) }
    androidx.compose.runtime.LaunchedEffect(pendingLocate, groups) {
        val p = pendingLocate ?: return@LaunchedEffect
        if (groupMode != p.second) return@LaunchedEffect // 等模式切换生效
        if (groups === groupsAtRequest) return@LaunchedEffect // 等新分组发射（避免用旧布局算索引）
        val index = flatGridIndex(groups, p.first)
        if (index >= 0) gridState.animateScrollToItem(index)
        pendingLocate = null
        groupsAtRequest = null
    }

    // 局部玻璃采样层：记录大标题 + 网格，供悬浮工具条折射。
    // 仅在有玻璃面板可见（含退出动画期间延迟 320ms）时挂载，
    // 避免网格每帧额外记录一份到 GPU 层（曾致 Skia scratch 缓存从 27MB 涨到 171MB）。
    val backdrop: LayerBackdrop = rememberLayerBackdrop { drawContent() }
    val samplingNeeded = selectionMode
    var backdropSampling by remember { mutableStateOf(samplingNeeded) }
    LaunchedEffect(samplingNeeded) {
        if (samplingNeeded) {
            backdropSampling = true
        } else {
            delay(320)
            backdropSampling = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (backdropSampling) Modifier.layerBackdrop(backdrop) else Modifier
                ),
        ) {
            // 多选时隐藏标题栏，为采样层外的操作条腾出顶部空间
            if (!selectionMode) {
                // 照片/视频细分（随 filtered 走，筛选后同样生效）
                val (photoCount, videoCount) = remember(filtered) {
                    var p = 0
                    var v = 0
                    for (item in filtered) {
                        if (item.isVideo) v++ else p++
                    }
                    p to v
                }
                TimelineHeader(
                    total = filtered.size,
                    photoCount = photoCount,
                    videoCount = videoCount,
                    filterActive = filter.isActive,
                    sort = sort,
                    onToggleSort = vm::toggleSort,
                    onOpenFilter = { filterOpen = !filterOpen },
                    onOpenSettings = onOpenSettings,
                )
            } else {
                Spacer(Modifier.height(68.dp))
            }

            // 顶部筛选条：点标题栏 Tune 钮展开/收起，chip 即时生效
            AnimatedVisibility(
                visible = filterOpen && !selectionMode,
                enter = expandVertically(tween(220)) + fadeIn(tween(160)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
            ) {
                FilterChipBar(
                    filter = filter,
                    liveScanning = liveScanning,
                    onToggleType = vm::toggleTypeFilter,
                    onToggleFavorite = vm::toggleFavoriteFilter,
                    onToggleLive = vm::toggleLiveOnly,
                    onClear = { vm.clearFilter() },
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading && groups.isEmpty() -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(36.dp)
                                .align(Alignment.Center),
                        )
                    }

                    filter.liveOnly && liveScanning -> {
                        // 实况过滤：首次启用需全量识别一遍（读 JPEG 文件头），先给出进度态
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(34.dp),
                                strokeWidth = 3.dp,
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "正在识别实况照片…",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    filtered.isEmpty() -> {
                        if (!filter.isActive) {
                            EmptyContent(
                                icon = Icons.Outlined.ImageNotSupported,
                                title = "还没有照片或视频",
                                subtitle = "用相机拍摄的照片和录制的视频会自动出现在这里",
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            EmptyContent(
                                icon = if (filter.types == setOf(MediaType.VIDEO)) {
                                    Icons.Outlined.VideoLibrary
                                } else {
                                    Icons.Outlined.ImageNotSupported
                                },
                                title = "没有符合条件的内容",
                                subtitle = "换一个筛选条件试试",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    else -> {
                        GroupedMediaGrid(
                            groups = groups,
                            selected = selection,
                            selectionMode = selectionMode,
                            onCellClick = { item ->
                                if (selectionMode) {
                                    vm.toggleSelect(item.id)
                                } else {
                                    when (groupMode) {
                                        // 按日：打开预览
                                        GroupMode.DAY -> {
                                            val index = filtered.indexOfFirst { it.id == item.id }
                                            if (index >= 0) onOpenViewer(filtered, index)
                                        }
                                        // 按月：钻取到日视图（列数 8 → 4/5），定位到该项
                                        GroupMode.MONTH -> {
                                            groupsAtRequest = groups
                                            pendingLocate = item.id to GroupMode.DAY
                                            vm.setGroupMode(GroupMode.DAY)
                                        }
                                        // 按年：钻取到月视图（列数 16 → 8），定位到该项
                                        GroupMode.YEAR -> {
                                            groupsAtRequest = groups
                                            pendingLocate = item.id to GroupMode.MONTH
                                            vm.setGroupMode(GroupMode.MONTH)
                                        }
                                    }
                                }
                            },
                            onCellLongClick = { vm.toggleSelect(it.id) },
                            bigHeaders = groupMode != GroupMode.DAY,
                            showHints = groupMode == GroupMode.DAY,
                            square = groupMode == GroupMode.YEAR,
                            scrubbing = thumbSelected || rapidScrolling,
                            onThumbSelectedChange = { thumbSelected = it },
                            gridState = gridState,
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier
                                .fillMaxSize()
                                // 捏合只体现列数/分组变化（过半程提交换档），不对整个区域做图层缩放
                                .pinchToAdjustColumns(
                                    enabled = !selectionMode,
                                    canToggle = { spread ->
                                        if (spread) stepIndex < ZOOM_STEPS.lastIndex else stepIndex > 0
                                    },
                                    stepLogSpan = { spread ->
                                        // 从 stepIndex 现算当前列数（委托属性，读到的是最新值）；
                                        // 不能用外层 val columns —— pointerInput 协程常驻，捕获到的会是过期值，
                                        // 导致换档后向内档 full = ln(1) = 0，永远等不到提交
                                        val cur = ZOOM_STEPS[stepIndex].columns.toFloat()
                                        val target = ZOOM_STEPS[
                                            (if (spread) stepIndex + 1 else stepIndex - 1)
                                                .coerceIn(0, ZOOM_STEPS.lastIndex)
                                        ].columns.toFloat()
                                        ln(if (spread) target / cur else cur / target)
                                    },
                                    onCommit = { spread ->
                                        val target = (if (spread) stepIndex + 1 else stepIndex - 1)
                                            .coerceIn(0, ZOOM_STEPS.lastIndex)
                                        android.util.Log.d(
                                            "PinchStep",
                                            "commit spread=$spread step=$stepIndex target=$target " +
                                                "mode=${ZOOM_STEPS.getOrNull(target)?.mode}"
                                        )
                                        if (target != stepIndex) {
                                            stepIndex = target
                                            vm.setGroupMode(ZOOM_STEPS[target].mode)
                                        }
                                    },
                                ),
                        )
                    }
                }

                // 右边缘"进度条式"快速就位条（刻度/年份胶囊覆盖层；拖动由库接管，见 GroupedMediaGrid）
                if (!selectionMode && groups.isNotEmpty()) {
                    TimelineScrubBar(
                        groups = groups,
                        gridState = gridState,
                        groupMode = groupMode,
                        expanded = thumbSelected,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }

        // -------------------------------------------------- 多选操作条
        // 必须留在 layerBackdrop 采样层之外（玻璃不能采样自身所在层，否则 hwui 栈溢出）。
        // 其高度由上方 Spacer 在采样层内预留，因此不与网格内容重叠。
        if (selectionMode) {
            SelectionBar(
                backdrop = backdrop,
                count = selection.size,
                onSelectAll = { vm.toggleSelectAll(filtered.map { it.id }) },
                onFavorite = if (canFavorite) ({
                    vm.setFavorite(selectedItems, true)
                }) else null,
                onDelete = { vm.requestDelete(selectedItems) },
                onClose = { vm.clearSelection() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 大标题

@Composable
private fun TimelineHeader(
    total: Int,
    photoCount: Int,
    videoCount: Int,
    filterActive: Boolean,
    sort: SortOrder,
    onToggleSort: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "时光",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (filterActive) {
                    "筛选出 ${formatCount(total)} 项 · 照片 ${formatCount(photoCount)} · 视频 ${formatCount(videoCount)}"
                } else {
                    "共 ${formatCount(total)} 项 · 照片 ${formatCount(photoCount)} · 视频 ${formatCount(videoCount)}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (filterActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // 排序与筛选并排在标题右侧。
        // 这两个按钮位于 layerBackdrop 采样层之内，因此不能用 liquidGlass：
        // 玻璃采样到自身所在层会让 hwui 的 MiBackgroundBlurBlend 递归栈溢出。
        HeaderIconButton(
            icon = if (sort == SortOrder.NEWEST) {
                Icons.Outlined.ArrowDownward
            } else {
                Icons.Outlined.ArrowUpward
            },
            contentDescription = if (sort == SortOrder.NEWEST) "最新在前" else "最早在前",
            onClick = onToggleSort,
        )
        Spacer(Modifier.width(8.dp))
        HeaderIconButton(
            icon = Icons.Outlined.Tune,
            contentDescription = "筛选",
            onClick = onOpenFilter,
            active = filterActive,
        )
        Spacer(Modifier.width(8.dp))
        HeaderIconButton(
            icon = Icons.Outlined.Settings,
            contentDescription = "设置",
            onClick = onOpenSettings,
        )
    }
}

/** 标题栏图标按钮：纯色圆底，避开玻璃采样层的自引用问题 */
@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, Springs.bouncy, label = "headerBtn")
    val bg by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        animationSpec = Springs.color,
        label = "headerBtnBg",
    )
    val fg by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = Springs.color,
        label = "headerBtnFg",
    )

    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * 双指捏合换档手势（MIUI PinchManager 同规则思路，但以“列数变化”直接呈现）：
 *
 * - 两指间距变化超过 1.2×touchSlop 才进入捏合（MIUI setScaleMinSpan），
 *   进入前不消费，单指滚动/点击/长按完整透传。
 * - 进入瞬间锁定方向：张开=向大图档收拢（日），合拢=向密集档铺开（月/年）；
 *   方向在档位边界（最日档再张开、最年档再合拢）不支持时本次捏合不生效。
 * - 一次捏合只走一档（MIUI mTargetViewMode 单步计算）；
 *   进度按对数比例推进并钳制 0..1，**进度过半程即实时提交换档**（本次手势后续不再变化），
 *   未过半程抬手则完全不换档。不对网格做任何图层缩放——缩放范围始终是列表本身。
 * - 快速捏合：进入首帧间距偏离即 >10%，直接提交（MIUI mIsFastScale）。
 * - 提交后继续消费事件直到手指全部抬起，避免残余手指误滚/误点。
 */
private fun Modifier.pinchToAdjustColumns(
    enabled: Boolean,
    /** 该方向当前是否可换档（实时读取，避免 pointerInput 捕获过期值） */
    canToggle: (spread: Boolean) -> Boolean,
    /** ln(整档所需间距比)，恒 >0：过半程判定用 */
    stepLogSpan: (spread: Boolean) -> Float,
    /** 过半程（或快速捏合）时触发，一次手势最多一次 */
    onCommit: (spread: Boolean) -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    val minSpan = viewConfiguration.touchSlop * 1.2f
    awaitEachGesture {
        // requireUnconsumed = false：即使子节点先动了也能观察到按下
        awaitFirstDown(requireUnconsumed = false)
        var pinching = false
        var supported = true
        var spread = false
        var startDistance = 0f
        var committed = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val active = event.changes.filter { it.pressed }
            when {
                active.size >= 2 -> {
                    val distance = (active[0].position - active[1].position).getDistance()
                    if (!pinching) {
                        if (startDistance <= 1f) {
                            startDistance = distance
                        } else if (abs(distance - startDistance) > minSpan) {
                            // 过门槛：锁定方向，判定该方向是否支持换档
                            spread = distance > startDistance
                            supported = canToggle(spread)
                            android.util.Log.d(
                                "PinchStep",
                                "gate spread=$spread supported=$supported"
                            )
                            if (supported) {
                                pinching = true
                                // 快速捏合：首帧偏离即 >10%，直接提交
                                if (abs(distance / startDistance - 1f) > 0.1f) {
                                    committed = true
                                    onCommit(spread)
                                }
                            }
                        }
                    } else if (!committed) {
                        // 对数进度：跨过整档所需的间距比才记满 1
                        val moved = if (spread) distance / startDistance else startDistance / distance
                        val full = stepLogSpan(spread)
                        if (moved > 1f && full > 0f) {
                            val progress = (ln(moved) / full).coerceIn(0f, 1f)
                            if (progress >= 0.5f) {
                                committed = true
                                onCommit(spread)
                            }
                        }
                    }
                    event.changes.forEach { it.consume() }
                }

                // 所有手指都抬起：结束本次捏合
                active.isEmpty() -> {
                    return@awaitEachGesture
                }

                // 捏合后还剩一根手指：继续消费，等它也抬起，避免剩余手指误滚/误点
                pinching -> event.changes.forEach { it.consume() }

                // 门槛前的双指/纯单指：什么都不做，滚动和点击原样透传
            }
        }
    }
}

