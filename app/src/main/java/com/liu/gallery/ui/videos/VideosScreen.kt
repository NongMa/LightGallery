package com.liu.gallery.ui.videos

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liu.gallery.data.MediaItem
import com.liu.gallery.ui.components.EmptyContent
import com.liu.gallery.ui.components.MediaCell
import com.liu.gallery.ui.components.MediaPreloadEffect
import com.liu.gallery.ui.components.SelectionBar
import com.liu.gallery.ui.glass.Springs
import com.liu.gallery.util.formatCount
import com.liu.gallery.vm.AlbumViewModel

/** 视频页（iOS 风格）：大标题 + 悬浮玻璃排序钮 + 16:9 宽卡 */
@Composable
fun VideosScreen(
    vm: AlbumViewModel,
    contentPadding: PaddingValues,
    onOpenViewer: (List<MediaItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    canFavorite: Boolean = true,
) {
    val videos by vm.videos.collectAsState()
    val loading by vm.loading.collectAsState()
    val selection by vm.selection.collectAsState()
    val sort by vm.sort.collectAsState()

    val selectionMode = selection.isNotEmpty()
    val selectedItems = remember(videos, selection) { videos.filter { it.id in selection } }

    val backdrop: LayerBackdrop = rememberLayerBackdrop { drawContent() }
    var headerHeight: Dp by remember { androidx.compose.runtime.mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val gridState = rememberLazyGridState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { px ->
                        headerHeight = with(density) { px.height.toDp() }
                    }
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "视频",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "共 ${formatCount(videos.size)} 个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 排序按钮固定在标题右侧，与时光页一致；
                // 纯色圆底避开玻璃采样层自引用的崩溃问题。
                val sortInteraction = remember { MutableInteractionSource() }
                val sortPressed by sortInteraction.collectIsPressedAsState()
                val sortScale by animateFloatAsState(
                    if (sortPressed) 0.92f else 1f,
                    Springs.bouncy,
                    label = "sortBtn",
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer { scaleX = sortScale; scaleY = sortScale }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .clickable(
                            interactionSource = sortInteraction,
                            indication = null,
                            onClick = vm::toggleSort,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (sort == com.liu.gallery.data.SortOrder.NEWEST) {
                            Icons.Outlined.ArrowDownward
                        } else {
                            Icons.Outlined.ArrowUpward
                        },
                        contentDescription = if (sort == com.liu.gallery.data.SortOrder.NEWEST) {
                            "最新在前"
                        } else {
                            "最早在前"
                        },
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading && videos.isEmpty() -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(36.dp)
                                .align(Alignment.Center),
                        )
                    }

                    videos.isEmpty() -> {
                        EmptyContent(
                            icon = Icons.Outlined.VideocamOff,
                            title = "暂无视频",
                            subtitle = "录制的视频会带时长角标显示在这里",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    else -> {
                        // MIUI 同规则方向感知预取（视频帧预热进 LRU，尺寸与解码恒定一致）
                        MediaPreloadEffect(
                            gridState = gridState,
                            items = videos,
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 88.dp),
                        ) {
                            items(videos, key = { it.id }) { item ->
                                MediaCell(
                                    item = item,
                                    selected = selectionMode && item.id in selection,
                                    onClick = {
                                        if (selectionMode) {
                                            vm.toggleSelect(item.id)
                                        } else {
                                            val index = videos.indexOfFirst { it.id == item.id }
                                            if (index >= 0) onOpenViewer(videos, index)
                                        }
                                    },
                                    onLongClick = { vm.toggleSelect(item.id) },
                                    aspect = 16f / 9f,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 多选操作条（排序按钮已移入标题栏）
        if (selectionMode) {
            SelectionBar(
                backdrop = backdrop,
                count = selection.size,
                onSelectAll = { vm.toggleSelectAll(videos.map { it.id }) },
                onFavorite = if (canFavorite) ({
                    vm.setFavorite(selectedItems, true)
                }) else null,
                onDelete = { vm.requestDelete(selectedItems) },
                onClose = { vm.clearSelection() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        }
    }
}
