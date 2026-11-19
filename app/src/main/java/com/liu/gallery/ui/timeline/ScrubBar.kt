package com.liu.gallery.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max
import com.liu.gallery.data.GroupMode
import com.liu.gallery.data.MediaGroup

/**
 * 右边缘「快速就位条」覆盖层（系统相册 / MIUI 相册式），拖动由 LazyVerticalGridScrollbar 库接管
 * （见 GroupedMediaGrid；库拇指透明，只当命中/拖动感应区，命中宽 36dp）。本组件按两种形态绘制：
 * - 普通滚动（网格自身惯性/手势滚动）：仅显示一个很短的白色拖动条，提示可上下拖
 * - 实际拖动就位条（手指按压在就位条上）：完整形态 = 白色细轨道 + 灰点刻度 + 白色圆形旋钮（≡）
 *   + 年份胶囊（仅「按年」）
 * - 默认隐藏，滚动或拖动时淡入，停止约 1.5 秒后淡出
 */
@Composable
internal fun TimelineScrubBar(
    groups: List<MediaGroup>,
    gridState: LazyGridState,
    groupMode: GroupMode,
    /** true = 手指正在就位条上按压拖动（库 isThumbSelected），显示完整形态 */
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    var rootHeightPx by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }

    // 平铺总条目数（每组 1 个头 + N 个单元格）
    val totalFlat = remember(groups) {
        groups.sumOf { 1 + it.items.size }
    }
    // 刻度点扁平下标：按月＝每月一刻度；按日＝月变更处一刻度；按年＝每年一刻度
    val yearStarts = remember(groups) {
        val seen = HashSet<Int>()
        val out = ArrayList<Pair<Int, Int>>(groups.size)
        var idx = 0
        for (g in groups) {
            val year = g.key.substringBefore('-').toIntOrNull()
            if (year != null && seen.add(year)) out.add(year to idx)
            idx += 1 + g.items.size
        }
        out
    }
    val tickStarts: List<Int> = remember(groups, groupMode) {
        when (groupMode) {
            GroupMode.YEAR -> yearStarts.map { it.second }
            GroupMode.MONTH -> {
                val out = ArrayList<Int>(groups.size)
                var idx = 0
                for (g in groups) { out.add(idx); idx += 1 + g.items.size }
                out
            }
            GroupMode.DAY -> {
                val out = ArrayList<Int>()
                var idx = 0
                var lastMonthKey: String? = null
                for (g in groups) {
                    val ym = g.key.take(7)
                    if (ym != lastMonthKey) { out.add(idx); lastMonthKey = ym }
                    idx += 1 + g.items.size
                }
                out
            }
        }
    }

    val visibleCount = gridState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val maxFirst = max(1, totalFlat - visibleCount)

    // 滚动或拖动时出现，停止约 1.5 秒后隐藏
    val isScrolling = gridState.isScrollInProgress
    LaunchedEffect(isScrolling, expanded) {
        if (isScrolling || expanded) {
            revealed = true
        } else if (revealed) {
            delay(1500)
            if (!gridState.isScrollInProgress && !expanded) revealed = false
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val chipHeightDp = 30.dp
    val chipHeightPx = with(density) { chipHeightDp.toPx() }
    val minGapPx = with(density) { 2.dp.toPx() }
    val padVPx = with(density) { 24.dp.toPx() }
    val anchorWidthDp = 26.dp
    val trackWidthDp = 16.dp    // 视觉轨道宽度（MIUI 式细条）
    val knobSizeDp = 26.dp      // 白色圆形旋钮，比轨道宽、左右各外溢 5dp
    val knobSizePx = with(density) { knobSizeDp.toPx() }
    val shortWidthDp = 20.dp    // 普通滚动时的短拖动条
    val shortHeightDp = 48.dp
    val shortHeightPx = with(density) { shortHeightDp.toPx() }
    fun fractionOf(flatIndex: Int): Float =
        (flatIndex.toFloat() / maxFirst).coerceIn(0f, 1f)
    fun pxToDp(px: Float) = with(density) { px.toDp() }

    // 去掉上下内边距后的可用高度（轨道与短条共用）
    val innerPx = (rootHeightPx - 2 * padVPx).coerceAtLeast(1f)

    // 年份胶囊去重叠（仅按年模式）
    val keptChipYears: List<Int> = remember(yearStarts, innerPx, maxFirst) {
        if (rootHeightPx <= 0 || yearStarts.isEmpty()) return@remember emptyList()
        val avail = (innerPx - chipHeightPx).coerceAtLeast(0f)
        val sorted = yearStarts.sortedBy { fractionOf(it.second) }
        val kept = ArrayList<Int>(sorted.size)
        var lastBottom = Float.NEGATIVE_INFINITY
        for ((year, start) in sorted) {
            val y = fractionOf(start) * avail
            if (kept.isEmpty() || y >= lastBottom) {
                kept.add(year)
                lastBottom = y + chipHeightPx + minGapPx
            }
        }
        kept
    }

    val firstIndex = gridState.firstVisibleItemIndex

    AnimatedVisibility(
        visible = revealed,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(250)),
        modifier = modifier,
    ) {
        // 透明覆盖层：贴右、无内边距；onSizeChanged 测整高，轨道/短条共用
        Box(
            modifier = Modifier
                .width(110.dp)
                .fillMaxHeight()
                .onSizeChanged { rootHeightPx = it.height }
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // —— 完整形态：手指正在就位条上按压拖动 ——
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier
                    .width(anchorWidthDp)
                    .fillMaxHeight(),
            ) {
                Box(modifier = Modifier.fillMaxHeight()) {
                    // 白色细轨道（圆角胶囊形，居中）
                    Box(
                        modifier = Modifier
                            .width(trackWidthDp)
                            .fillMaxHeight()
                            .align(Alignment.Center)
                            .shadow(2.dp, RoundedCornerShape(50))
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.96f))
                            .border(0.5.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(50)),
                    )
                    if (rootHeightPx > 0) {
                        // 刻度点（灰点列，居中于轨道）
                        val dotTravel = (innerPx - with(density) { 4.dp.toPx() }).coerceAtLeast(0f)
                        tickStarts.forEach { start ->
                            val y = fractionOf(start) * dotTravel
                            Box(
                                modifier = Modifier
                                    .padding(top = pxToDp(y))
                                    .align(Alignment.TopCenter)
                                    .size(4.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.32f)),
                            )
                        }

                        // 拖拽旋钮：白色圆形 + ≡ 抓握线，比轨道略宽
                        val knobTravel = (innerPx - knobSizePx).coerceAtLeast(0f)
                        val knobY = fractionOf(firstIndex) * knobTravel
                        Box(
                            modifier = Modifier
                                .padding(top = pxToDp(knobY))
                                .align(Alignment.TopCenter)
                                .size(knobSizeDp)
                                .shadow(3.dp, RoundedCornerShape(50))
                                .clip(RoundedCornerShape(50))
                                .background(Color.White)
                                .border(0.5.dp, Color.Black.copy(alpha = 0.12f), RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .width(9.dp)
                                            .height(1.4.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0xFF6E6E6E).copy(alpha = 0.85f)),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // —— 普通滚动：仅显示很短的拖动条，提示可上下拖 ——
            AnimatedVisibility(
                visible = !expanded,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier
                    .width(anchorWidthDp)
                    .fillMaxHeight(),
            ) {
                Box(modifier = Modifier.fillMaxHeight()) {
                    if (rootHeightPx > 0) {
                        val shortTravel = (innerPx - shortHeightPx).coerceAtLeast(0f)
                        val shortY = fractionOf(firstIndex) * shortTravel
                        Box(
                            modifier = Modifier
                                .padding(top = pxToDp(shortY))
                                .align(Alignment.TopCenter)
                                .width(shortWidthDp)
                                .height(shortHeightDp)
                                .shadow(3.dp, RoundedCornerShape(50))
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.97f))
                                .border(0.5.dp, Color.Black.copy(alpha = 0.12f), RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .width(8.dp)
                                            .height(1.4.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0xFF6E6E6E).copy(alpha = 0.85f)),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 年份胶囊：仅「按年」且仅完整形态（拖动中）显示，放在旋钮左侧
            if (expanded && groupMode == GroupMode.YEAR && rootHeightPx > 0) {
                val avail = (innerPx - chipHeightPx).coerceAtLeast(0f)
                yearStarts.forEach { (year, start) ->
                    if (year !in keptChipYears) return@forEach
                    val y = fractionOf(start) * avail
                    Box(
                        modifier = Modifier
                            .padding(top = pxToDp(y), end = anchorWidthDp + 8.dp)
                            .align(Alignment.TopEnd)
                            .width(64.dp)
                            .height(chipHeightDp)
                            .shadow(2.dp, RoundedCornerShape(50))
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.98f))
                            .border(0.5.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$year",
                            color = Color(0xFF3A3A3A),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
