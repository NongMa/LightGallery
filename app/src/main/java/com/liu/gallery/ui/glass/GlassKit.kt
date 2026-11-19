package com.liu.gallery.ui.glass

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 液态玻璃基础组件：
 * - 玻璃面 = 背景采样 + 振彩 + 模糊 + 透镜折射 + 边缘高光 + 投影（backdrop 库）
 * - Android 12 以下自动降级为半透明着色（库内部跳过 RenderEffect）
 * - 交互统一使用弹簧动画与触感反馈，对齐 iOS 27 相册手感
 */

/** 弹簧参数：贴近 iOS 的回弹 */
object Springs {
    val bouncy = spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
    val snappy = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
    val color = spring<Color>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
}

/** 玻璃表面着色：浅色模式白玻璃、深色模式黑玻璃 */
@Composable
fun rememberGlassTint(alpha: Float = 0.45f): Color =
    if (androidx.compose.foundation.isSystemInDarkTheme()) {
        Color.Black.copy(alpha = (alpha * 0.85f).coerceAtMost(0.6f))
    } else {
        Color.White.copy(alpha = alpha)
    }

/**
 * 标准液态玻璃面。
 * 效果顺序遵循库要求：色彩滤镜 ⇒ 模糊 ⇒ 透镜。
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: Shape,
    tint: Color,
    blurRadius: Dp = 14.dp,
    refractionHeight: Dp = 7.dp,
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
        // lens 要求 refractionHeight ≤ shape 最小圆角，且仅 Android 13+ 生效
        lens(refractionHeight.toPx(), refractionHeight.toPx() * 2f)
    },
    onDrawSurface = { drawRect(tint) },
)

/** 按压微缩放（iOS 式“按下变小/内容放大”反馈） */
@Composable
fun Modifier.pressScale(
    pressed: Boolean,
    scaleDown: Float = 0.96f,
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = Springs.bouncy,
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 按压状态（供 Cell 使用） */
@Composable
fun rememberPressState(): Boolean {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    return pressed
}

/** 玻璃圆形图标按钮 */
@Composable
fun GlassIconButton(
    backdrop: Backdrop,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, Springs.bouncy, label = "iconBtn")

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .liquidGlass(
                backdrop = backdrop,
                shape = CircleShape,
                tint = rememberGlassTint(0.34f),
                blurRadius = 12.dp,
                refractionHeight = 6.dp,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

/** 分段控件（日 / 月 / 年）：玻璃胶囊 + 弹性滑块 */
@Composable
fun GlassSegmented(
    backdrop: Backdrop,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .height(40.dp)
            .liquidGlass(
                backdrop = backdrop,
                shape = RoundedCornerShape(50),
                tint = rememberGlassTint(0.4f),
                blurRadius = 12.dp,
                refractionHeight = 6.dp,
            )
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                animationSpec = Springs.color,
                label = "segBg",
            )
            val fg by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = Springs.color,
                label = "segFg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (!selected) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** 筛选胶囊组（全部 / 图片 / 视频 / 收藏）：玻璃条内滚动 */
@Composable
fun GlassFilterRow(
    backdrop: Backdrop,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .height(44.dp)
            .liquidGlass(
                backdrop = backdrop,
                shape = RoundedCornerShape(50),
                tint = rememberGlassTint(0.4f),
                blurRadius = 12.dp,
                refractionHeight = 6.dp,
            )
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                animationSpec = Springs.color,
                label = "chipBg",
            )
            val fg by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = Springs.color,
                label = "chipFg",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (!selected) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = fg,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** 通用玻璃胶囊容器：内容绘制在玻璃之上 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    tintAlpha: Float = 0.42f,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.liquidGlass(
            backdrop = backdrop,
            shape = shape,
            tint = rememberGlassTint(tintAlpha),
        ),
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
