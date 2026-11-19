package com.liu.gallery.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liu.gallery.data.MediaItem
import com.liu.gallery.ui.albums.AlbumDetailScreen
import com.liu.gallery.ui.albums.AlbumsScreen
import com.liu.gallery.ui.glass.Springs
import com.liu.gallery.ui.glass.liquidGlass
import com.liu.gallery.ui.glass.rememberGlassTint
import com.liu.gallery.ui.settings.SettingsScreen
import com.liu.gallery.ui.timeline.TimelineScreen
import com.liu.gallery.ui.videos.VideosScreen
import com.liu.gallery.ui.viewer.ViewerScreen
import com.liu.gallery.vm.AlbumViewModel
import kotlinx.coroutines.delay

/**
 * 应用外壳：
 * - 全局液态玻璃背景（backdrop 采样），底部悬浮玻璃标签栏（iOS 27 风格）
 * - 权限引导、系统授权回调、全局 Toast、页面弹性转场
 * - 无任何网络权限，全部数据来自本机 MediaStore
 */
@Composable
fun AlbumApp(vm: AlbumViewModel = viewModel()) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    // 顶层页与下钻的相册详情都保留悬浮标签栏（与 iOS 一致）
    val topLevel = route in setOf("timeline", "albums", "videos")
    val showTabBar = topLevel || route?.startsWith("album/") == true
    val selection by vm.selection.collectAsState()
    val selectionActive = selection.isNotEmpty()
    val canFavorite = Build.VERSION.SDK_INT >= 31

    // -------------------------------------------------------------- 权限
    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    fun hasMediaPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        ).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        Build.VERSION.SDK_INT >= 33 ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_GRANTED

        else ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
    }

    var permissionGranted by remember { mutableStateOf<Boolean?>(null) }
    var permissionDeniedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val ok = hasMediaPermission()
        permissionGranted = ok
        if (ok) vm.refresh() else permissionDeniedOnce = true
    }

    LaunchedEffect(Unit) {
        if (hasMediaPermission()) {
            permissionGranted = true
            vm.refresh()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    // 从设置 / 其他应用返回时复查权限并刷新数据
    var firstResume by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResume) {
                    firstResume = false
                } else if (permissionGranted == true) {
                    vm.refresh()
                } else if (permissionGranted == false && hasMediaPermission()) {
                    permissionGranted = true
                    vm.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ------------------------------------------------------- 系统授权弹窗
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onConsentResult(result.resultCode)
    }
    val consent by vm.consent.collectAsState()
    LaunchedEffect(consent?.pendingIntent) {
        val pi = consent?.pendingIntent ?: return@LaunchedEffect
        try {
            consentLauncher.launch(
                IntentSenderRequest.Builder(pi.intentSender).build()
            )
        } catch (e: Exception) {
            vm.onConsentResult(Activity.RESULT_CANCELED)
        }
    }

    // ------------------------------------------------------------- Toast
    LaunchedEffect(Unit) {
        vm.toast.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------- 路由
    fun openViewer(list: List<MediaItem>, index: Int) {
        vm.openViewer(list, index)
        navController.navigate("viewer")
    }

    fun openAlbum(albumId: String) {
        navController.navigate("album/${Uri.encode(albumId)}")
    }

    fun navigateTop(target: String) {
        vm.clearSelection()
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // ------------------------------------------------------------- 外壳
    Box(modifier = Modifier.fillMaxSize()) {
        // 全局玻璃采样层：Scaffold 内容整体记录。
        // 仅在玻璃标签栏可见（含退出动画期间延迟 320ms）时挂载，
        // 避免网格每帧额外记录一份到 GPU 层。
        val bgColor = MaterialTheme.colorScheme.background
        val tabBarVisible = showTabBar && !selectionActive && permissionGranted == true
        var backdropSampling by remember { mutableStateOf(tabBarVisible) }
        LaunchedEffect(tabBarVisible) {
            if (tabBarVisible) {
                backdropSampling = true
            } else {
                delay(320)
                backdropSampling = false
            }
        }
        val backdrop = rememberLayerBackdrop {
            drawRect(bgColor)
            drawContent()
        }

        // 内容以正常流铺满底部边缘：悬浮玻璃标签栏直接浮在网格之上采样（与 iOS 一致），
        // 否则标签栏只能折射到一条空背景，液态玻璃效果等于没有。
        // 顶部内边距仍留给顶部内容；滚动区底部通过各自的 contentPadding 预留 88dp，
        // 保证末行能滚动到标签栏之上。
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (backdropSampling) Modifier.layerBackdrop(backdrop) else Modifier
                ),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.statusBars,
        ) { padding ->
            when (permissionGranted) {
                null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                }

                false -> PermissionScreen(
                    showSettingsHint = permissionDeniedOnce,
                    onGrant = { permissionLauncher.launch(permissions) },
                    onOpenSettings = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )

                true -> NavHost(
                    navController = navController,
                    startDestination = "timeline",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        fadeIn(tween(220)) + scaleIn(
                            initialScale = 0.985f,
                            animationSpec = tween(260),
                        )
                    },
                    exitTransition = { fadeOut(tween(140)) },
                    popEnterTransition = { fadeIn(tween(200)) },
                    popExitTransition = {
                        fadeOut(tween(160)) + scaleOut(
                            targetScale = 0.99f,
                            animationSpec = tween(200),
                        )
                    },
                ) {
                    composable("timeline") {
                        TimelineScreen(
                            vm = vm,
                            contentPadding = padding,
                            onOpenViewer = ::openViewer,
                            canFavorite = canFavorite,
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("albums") {
                        AlbumsScreen(
                            vm = vm,
                            contentPadding = padding,
                            onOpenAlbum = ::openAlbum,
                        )
                    }
                    composable("videos") {
                        VideosScreen(
                            vm = vm,
                            contentPadding = padding,
                            onOpenViewer = ::openViewer,
                            canFavorite = canFavorite,
                        )
                    }
                    composable(
                        route = "album/{albumId}",
                        arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
                    ) { entry ->
                        AlbumDetailScreen(
                            vm = vm,
                            contentPadding = padding,
                            albumIdRaw = entry.arguments?.getString("albumId").orEmpty(),
                            onBack = { navController.popBackStack() },
                            onOpenViewer = ::openViewer,
                            canFavorite = canFavorite,
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            vm = vm,
                            contentPadding = padding,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("viewer") {
                        ViewerScreen(
                            vm = vm,
                            onClose = { navController.popBackStack() },
                        )
                    }
                }
            }
        }

        // 悬浮玻璃标签栏（位于采样层之外，可采样到下面的页面）
        AnimatedVisibility(
            visible = tabBarVisible,
            enter = fadeIn(tween(240)) + androidx.compose.animation.slideInVertically(
                animationSpec = tween(300),
                initialOffsetY = { it / 2 },
            ),
            exit = fadeOut(tween(160)) + androidx.compose.animation.slideOutVertically(
                animationSpec = tween(220),
                targetOffsetY = { it / 2 },
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter),
        ) {
            GlassTabBar(
                backdrop = backdrop,
                currentRoute = route,
                onNavigate = ::navigateTop,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 玻璃标签栏

private data class TabSpec(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun GlassTabBar(
    backdrop: LayerBackdrop,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val tabs = remember {
        listOf(
            TabSpec("timeline", "时光", Icons.Outlined.CalendarMonth),
            TabSpec("albums", "相册", Icons.Outlined.PhotoLibrary),
            TabSpec("videos", "视频", Icons.Outlined.VideoLibrary),
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp)
            .height(62.dp)
            .liquidGlass(
                backdrop = backdrop,
                shape = RoundedCornerShape(50),
                tint = rememberGlassTint(0.5f),
                blurRadius = 16.dp,
                refractionHeight = 8.dp,
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { tab ->
            // 当前路由匹配：顶层直达或该 tab 下钻的详情页
            val selected = currentRoute == tab.route ||
                (tab.route == "albums" && currentRoute?.startsWith("album/") == true)
            GlassTabItem(
                tab = tab,
                selected = selected,
                onClick = {
                    if (!selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onNavigate(tab.route)
                },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GlassTabItem(
    tab: TabSpec,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.93f else 1f,
        Springs.bouncy,
        label = "tabPress",
    )
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else Color.Transparent,
        Springs.color,
        label = "tabBg",
    )
    val fg by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        Springs.color,
        label = "tabFg",
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            color = fg,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            // 中文字形的下缘容易贴到圆角裁切边，这里留出 1dp 呼吸位
            modifier = Modifier.padding(bottom = 1.dp),
        )
    }
}

// ------------------------------------------------------------ 权限引导页

@Composable
private fun PermissionScreen(
    showSettingsHint: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 36.dp),
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
                    imageVector = Icons.Outlined.NoPhotography,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(
            text = "需要访问照片和视频",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "清影相册完全离线运行、不含任何网络权限。\n授权后即可浏览、整理本机的图片与视频。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 23.sp,
        )
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = onGrant,
            shape = CircleShape,
            modifier = Modifier.height(46.dp),
        ) {
            Text("授予访问权限", style = MaterialTheme.typography.labelLarge)
        }
        if (showSettingsHint) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenSettings) {
                Text(
                    "已拒绝？前往系统设置授权",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
