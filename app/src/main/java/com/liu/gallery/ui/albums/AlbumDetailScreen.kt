package com.liu.gallery.ui.albums

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liu.gallery.data.GroupMode
import com.liu.gallery.data.MediaItem
import com.liu.gallery.data.SortOrder
import com.liu.gallery.data.groupByDate
import com.liu.gallery.ui.components.GroupedMediaGrid
import com.liu.gallery.ui.components.SelectionBar
import com.liu.gallery.ui.glass.GlassIconButton
import com.liu.gallery.util.formatCount
import com.liu.gallery.vm.AlbumViewModel

/** 相册详情（iOS 风格）：大标题 + 悬浮玻璃返回/排序钮 + 该文件夹全部内容 */
@Composable
fun AlbumDetailScreen(
    vm: AlbumViewModel,
    contentPadding: PaddingValues,
    albumIdRaw: String,
    onBack: () -> Unit,
    onOpenViewer: (List<MediaItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    canFavorite: Boolean = true,
) {
    val albums by vm.albums.collectAsState()
    val loading by vm.loading.collectAsState()
    val selection by vm.selection.collectAsState()
    val sort by vm.sort.collectAsState()

    val albumId = remember(albumIdRaw) {
        try {
            Uri.decode(albumIdRaw)
        } catch (e: Exception) {
            albumIdRaw
        }
    }
    val album = albums.find { it.id == albumId } ?: albums.find { it.id == albumIdRaw }

    if (album == null) {
        if (loading) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
            }
        } else {
            LaunchedEffect(Unit) { onBack() }
        }
        return
    }

    val items = remember(album, sort) {
        if (sort == SortOrder.OLDEST) album.items.asReversed() else album.items
    }
    val groups = remember(items) { groupByDate(items, GroupMode.DAY) }
    val selectionMode = selection.isNotEmpty()
    val selectedItems = remember(items, selection) { items.filter { it.id in selection } }

    val backdrop: LayerBackdrop = rememberLayerBackdrop { drawContent() }
    var headerHeight: Dp by remember { androidx.compose.runtime.mutableStateOf(0.dp) }
    val density = LocalDensity.current

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
            // 大标题（为悬浮返回钮留出起始缩进）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { px ->
                        headerHeight = with(density) { px.height.toDp() }
                    }
                    .padding(start = 64.dp, end = 64.dp, top = 14.dp, bottom = 10.dp),
            ) {
                Text(
                    text = album.name,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatCount(album.count)} 项 · ${album.items.firstOrNull()?.relativePath ?: ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            GroupedMediaGrid(
                groups = groups,
                selected = selection,
                selectionMode = selectionMode,
                onCellClick = { item ->
                    if (selectionMode) {
                        vm.toggleSelect(item.id)
                    } else {
                        val index = items.indexOfFirst { it.id == item.id }
                        if (index >= 0) onOpenViewer(items, index)
                    }
                },
                onCellLongClick = { vm.toggleSelect(it.id) },
                bigHeaders = false,
                gridState = rememberLazyGridState(),
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 悬浮玻璃控件
        if (selectionMode) {
            SelectionBar(
                backdrop = backdrop,
                count = selection.size,
                onSelectAll = { vm.toggleSelectAll(items.map { it.id }) },
                onFavorite = if (canFavorite) ({
                    vm.setFavorite(selectedItems, true)
                }) else null,
                onDelete = { vm.requestDelete(selectedItems) },
                onClose = { vm.clearSelection() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        } else {
            GlassIconButton(
                backdrop = backdrop,
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 10.dp),
            )
            GlassIconButton(
                backdrop = backdrop,
                icon = {
                    Icon(
                        imageVector = if (sort == SortOrder.NEWEST) {
                            Icons.Outlined.ArrowDownward
                        } else {
                            Icons.Outlined.ArrowUpward
                        },
                        contentDescription = "切换排序",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = vm::toggleSort,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 10.dp),
            )
        }
    }
}
