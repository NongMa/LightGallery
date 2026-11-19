package com.liu.gallery.ui.viewer

import android.net.Uri
import android.os.Build
import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.liu.gallery.data.LivePhotoLoader
import com.liu.gallery.data.MediaItem
import com.liu.gallery.ui.components.MediaThumb
import com.liu.gallery.ui.info.InfoSheet
import com.liu.gallery.util.formatDateTimeFull
import com.liu.gallery.util.formatDuration
import com.liu.gallery.ui.glass.rememberGlassTint
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liu.gallery.vm.AlbumViewModel
import java.io.File

// 预览：媒体全屏黑底；常态上/下浮层为液态玻璃条（采样媒体 + 振彩 + 模糊）
private val ImmersiveBlack = Color.Black
private val FavoriteGold = Color(0xFFF5C04E)

/** 全宽液态玻璃条（无圆角、无透镜，仅振彩+模糊）：直接贴边覆盖状态栏/导航栏区域 */
private fun Modifier.liquidGlassBar(
    backdrop: com.kyant.backdrop.Backdrop,
    tint: Color,
    blurRadius: Dp,
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { androidx.compose.ui.graphics.RectangleShape },
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
    },
    onDrawSurface = { drawRect(tint) },
)

/**
 * 查看器：媒体全屏 + 上/下浮层栏。
 *
 * - 常态：图片/视频铺满整个屏幕，返回 + 文件名/类型的顶部栏、收藏 / 日期页码 / 属性 / 删除的底部栏浮在内容上
 * - 点击媒体切换到沉浸态（或视频真正在播放时自动进入）：上下栏收起，状态栏/导航栏一并隐藏，纯全屏；再点一下恢复
 */
@Composable
fun ViewerScreen(
    vm: AlbumViewModel,
    onClose: () -> Unit,
) {
    val viewer by vm.viewer.collectAsState()
    val v = viewer
    if (v == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val items = v.items
    val context = LocalContext.current
    val canFavorite = Build.VERSION.SDK_INT >= 31
    val pagerState = rememberPagerState(initialPage = v.index.coerceAtLeast(0)) { items.size }

    // 视频播放器
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    // 实况照片播放器（独立，循环）
    val livePlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            livePlayer.release()
        }
    }

    var showInfo by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<MediaItem?>(null) }
    // 当前页实况播放状态
    var livePlaying by remember { mutableStateOf(false) }
    // 用户是否手动隐藏上下栏（点图片切换）；视频播放时不受此影响
    var userHidden by remember { mutableStateOf(false) }
    // 视频实际播放中（player 监听驱动）——播放时自动沉浸黑底
    var videoPlaying by remember { mutableStateOf(false) }

    val currentPage = pagerState.currentPage
    val currentItem = items.getOrNull(currentPage)
    val currentIsVideo = currentItem?.isVideo == true

    // 当前项是否实况照片
    val currentIsLive by produceState(initialValue = false, key1 = currentItem?.id) {
        val item = currentItem
        value = item != null && !item.isVideo && LivePhotoLoader.isLivePhoto(context, item)
    }

    // 页码 → VM
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { vm.viewerPage(it) }
    }
    LaunchedEffect(v.index) {
        if (v.index != pagerState.currentPage && v.index in 0 until items.size) {
            pagerState.scrollToPage(v.index)
        }
    }

    // 视频播放状态监听：播放 → 沉浸黑底隐藏栏；暂停 → 恢复
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                videoPlaying = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 切页：视频自动播放；图片停止视频播放器；实况停止循环
    LaunchedEffect(currentPage, items) {
        val item = items.getOrNull(currentPage)
        livePlaying = false
        livePlayer.stop()
        livePlayer.clearMediaItems()
        if (item != null && item.isVideo) {
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
            player.prepare()
            player.playWhenReady = true
        } else {
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
        }
    }

    // 沉浸态：视频播放中自动进入；图片/实况由用户点按切换；隐藏状态栏+导航栏
    val systemDark = isSystemInDarkTheme()
    val chromeHidden = if (currentIsVideo) videoPlaying else userHidden
    val view = LocalView.current
    LaunchedEffect(chromeHidden, systemDark) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (chromeHidden) {
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.isAppearanceLightStatusBars = !systemDark
            controller.isAppearanceLightNavigationBars = !systemDark
        }
    }
    DisposableEffect(systemDark) {
        val ctx = view.context
        onDispose {
            val window = (ctx as? Activity)?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, view).apply {
                show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                isAppearanceLightStatusBars = !systemDark
                isAppearanceLightNavigationBars = !systemDark
            }
        }
    }

    // 媒体层 backdrop 采样（栏显示才开，沉浸/实况播放时关闭——避免无意义开销与 AndroidView 采样问题）
    val backdrop = rememberLayerBackdrop { drawContent() }
    var backdropSampling by remember { mutableStateOf(true) }
    LaunchedEffect(chromeHidden, livePlaying) {
        backdropSampling = !chromeHidden && !livePlaying
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ImmersiveBlack),
    ) {
        // -------------------------------------------------- 媒体层：全屏铺底（backdrop 采样源）
        Box(
            modifier = if (backdropSampling) Modifier.fillMaxSize().layerBackdrop(backdrop)
                       else Modifier.fillMaxSize(),
        ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> items.getOrNull(page)?.id ?: page },
            beyondViewportPageCount = 1,
            pageSpacing = 4.dp,
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) {
                VideoPage(
                    item = item,
                    player = player,
                    active = page == pagerState.currentPage,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    ZoomableImage(
                        item = item,
                        modifier = Modifier.fillMaxSize(),
                        onSingleTap = { userHidden = !userHidden },
                    )
                    // 实况循环视频覆盖层
                    if (page == pagerState.currentPage && livePlaying) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                }
                            },
                            update = { view ->
                                if (view.player != livePlayer) view.player = livePlayer
                            },
                            onRelease = { view -> view.player = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        }

        // -------------------------------------------------- 媒体层结束（实况按钮与提取 effect 不采样）
        // 实况播放按钮（仅实况照片未播放时，全屏中央，淡入淡出+缩放）
        val liveButtonProgress by animateFloatAsState(
            targetValue = if (currentIsLive && !livePlaying && currentItem != null) 1f else 0f,
            animationSpec = tween(220),
            label = "liveButton",
        )
        if (liveButtonProgress > 0.02f) {
            LivePlayButton(
                onPlay = { livePlaying = true },
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        alpha = liveButtonProgress
                        val s = 0.85f + 0.15f * liveButtonProgress
                        scaleX = s
                        scaleY = s
                    }
                    .align(Alignment.Center),
            )
        }
        // 提取视频并开播
        LaunchedEffect(livePlaying, currentItem) {
            if (livePlaying && currentItem != null && !currentItem.isVideo) {
                val file: File? = LivePhotoLoader.extractVideo(context, currentItem)
                if (file != null) {
                    livePlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
                    livePlayer.prepare()
                    livePlayer.playWhenReady = true
                } else {
                    livePlaying = false
                }
            }
        }

        // -------------------------------------------------- 上标题栏：浮动玻璃胶囊（沉浸时收起）
        AnimatedVisibility(
            visible = !chromeHidden,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { -it },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
        ) {
            ViewerTopBar(
                backdrop = backdrop,
                sampling = backdropSampling,
                item = currentItem,
                isLive = currentIsLive,
                livePlaying = livePlaying,
                onBack = onClose,
                onToggleLive = {
                    if (livePlaying) {
                        livePlaying = false
                        livePlayer.pause()
                    } else {
                        livePlaying = true
                    }
                },
            )
        }

        // -------------------------------------------------- 下操作栏：浮动玻璃胶囊（沉浸时收起）
        AnimatedVisibility(
            visible = !chromeHidden,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { it },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it },
        ) {
            ViewerBottomBar(
                backdrop = backdrop,
                sampling = backdropSampling,
                item = currentItem,
                pageText = "${(currentPage + 1).coerceAtLeast(1)} / ${items.size}",
                onFavorite = {
                    currentItem?.let { vm.setFavorite(listOf(it), !it.isFavorite) }
                },
                onInfo = { showInfo = true },
                onDelete = { deleteTarget = currentItem },
                canFavorite = canFavorite,
            )
        }

        // 属性面板
        if (showInfo && currentItem != null) {
            InfoSheet(
                item = currentItem,
                onDismiss = { showInfo = false },
                onToggleFavorite = { fav -> vm.setFavorite(listOf(currentItem), fav) },
                onRename = { name -> vm.rename(currentItem, name) },
                onDelete = { vm.requestDelete(listOf(currentItem)) },
                canFavorite = canFavorite,
            )
        }

        // 删除确认
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("移入回收站？") },
                text = { Text("「${target.name}」将被移入回收站，之后可在系统相册中恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        vm.requestDelete(listOf(target))
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                },
            )
        }
    }
}

// ------------------------------------------------------------------ 上标题栏

@Composable
private fun ViewerTopBar(
    backdrop: LayerBackdrop,
    sampling: Boolean,
    item: MediaItem?,
    isLive: Boolean,
    livePlaying: Boolean,
    onBack: () -> Unit,
    onToggleLive: () -> Unit,
) {
    val tint = rememberGlassTint(0.6f)
    val glass = if (sampling) {
        Modifier.liquidGlassBar(backdrop = backdrop, tint = tint, blurRadius = 18.dp)
    } else {
        // 实况播放中：采样关闭，退化为纯半透明玻璃
        Modifier.background(tint)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(glass)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            // 标题信息（含实况徽标）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = item?.name ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                )
                Text(
                    text = when {
                        item?.isVideo == true -> formatDuration(item.durationMillis)
                        isLive -> if (livePlaying) "实况 · 播放中" else "实况照片"
                        else -> "图片"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (isLive) {
                IconButton(onClick = onToggleLive, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = if (livePlaying) "暂停实况" else "播放实况",
                        tint = if (livePlaying) FavoriteGold else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 下操作栏

@Composable
private fun ViewerBottomBar(
    backdrop: LayerBackdrop,
    sampling: Boolean,
    item: MediaItem?,
    pageText: String,
    onFavorite: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    canFavorite: Boolean,
) {
    val tint = rememberGlassTint(0.6f)
    val glass = if (sampling) {
        Modifier.liquidGlassBar(backdrop = backdrop, tint = tint, blurRadius = 18.dp)
    } else {
        // 实况播放中：采样关闭，退化为纯半透明玻璃
        Modifier.background(tint)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(glass)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canFavorite && item != null) {
                IconButton(onClick = onFavorite, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = if (item.isFavorite) Icons.Filled.Favorite
                        else Icons.Filled.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (item.isFavorite) FavoriteGold else MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Spacer(Modifier.size(44.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatDateTimeFull(item?.dateTakenMillis ?: 0L),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = pageText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onInfo, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "属性",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ------------------------------------------------------------ 实况播放按钮

@Composable
private fun LivePlayButton(onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "livePlay",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onPlay,
            )
            .background(Color.Black.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "播放实况",
            tint = Color.White,
            modifier = Modifier.size(36.dp),
        )
    }
}

// -------------------------------------------------------------- 可缩放图片

@Composable
private fun ZoomableImage(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onSingleTap: () -> Unit = {},
) {
    var scale by remember(item.id) { mutableStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var boxSize by remember(item.id) { mutableStateOf(IntSize.Zero) }

    fun clamp(
        newOffset: androidx.compose.ui.geometry.Offset,
        newScale: Float,
    ): androidx.compose.ui.geometry.Offset {
        if (boxSize.width <= 0 || boxSize.height <= 0) return androidx.compose.ui.geometry.Offset.Zero
        val maxX = (boxSize.width / 2f) * (newScale - 1f)
        val maxY = (boxSize.height / 2f) * (newScale - 1f)
        return androidx.compose.ui.geometry.Offset(
            newOffset.x.coerceIn(-maxX, maxX),
            newOffset.y.coerceIn(-maxY, maxY),
        )
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(item.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 6f)
                    if (next <= 1.01f) {
                        scale = 1f
                        offset = androidx.compose.ui.geometry.Offset.Zero
                    } else {
                        scale = next
                        offset = clamp(offset + pan, next)
                    }
                }
            }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = { position ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                        } else {
                            val target = 2.5f
                            val center = androidx.compose.ui.geometry.Offset(
                                size.width / 2f, size.height / 2f,
                            )
                            scale = target
                            offset = clamp(
                                (position - center) * (1f - target),
                                target,
                            )
                        }
                    },
                )
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.uri)
                .crossfade(120)
                .build(),
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

// ---------------------------------------------------------------- 视频页面

@Composable
private fun VideoPage(
    item: MediaItem,
    player: ExoPlayer,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    if (active) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerShowTimeoutMs = 4000
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                if (view.player != player) view.player = player
            },
            onRelease = { view -> view.player = null },
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            MediaThumb(
                item = item,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
