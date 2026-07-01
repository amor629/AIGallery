package com.example.aigallery.ui.detail

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.ui.LocalAnimatedVisibilityScope
import com.example.aigallery.ui.LocalSharedTransitionScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 图片详情页（沉浸式全屏浏览）
 *
 * 支持的手势：
 * ┌──────────────────────────────────────────────────────┐
 * │ 双指捏合      → 缩放（1x ～ 5x）                     │
 * │ 放大后单指拖  → 平移图片（限制在边界内）              │
 * │ 双击          → 放大到 2.5x / 还原到 1x              │
 * │ 单击          → 切换顶部栏显示 / 隐藏                 │
 * │ 正常比例下下滑 → 下滑返回（拖动 > 180px 触发退出）    │
 * │ 下滑不够 180px → 弹性回弹到原位                      │
 * └──────────────────────────────────────────────────────┘
 *
 * @param uri            媒体内容 URI（content://media/...）
 * @param fileName       文件名，显示在顶部栏
 * @param mediaType      IMAGE（手势查看）或 VIDEO（自动播放）
 * @param onNavigateBack 退出详情页的回调
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoDetailScreen(
    uri: Uri,
    fileName: String,
    mediaType: MediaType = MediaType.IMAGE,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    // 读取 CompositionLocal 中的共享元素 Scope（由 MainActivity 的 NavHost 提供）
    val sharedScope = LocalSharedTransitionScope.current
    val visScope    = LocalAnimatedVisibilityScope.current
    // 共享元素修饰符（图片和视频均复用）：与相册缩略图构成飞入/飞出过渡动画
    val sharedModifier: Modifier = if (sharedScope != null && visScope != null) {
        with(sharedScope) {
            Modifier.sharedElement(
                state = rememberSharedContentState("media_$uri"),
                animatedVisibilityScope = visScope
            )
        }
    } else Modifier

    // ================================================================
    // 状态定义
    // ================================================================

    /** 当前缩放比例（1f = 原始大小，最大 5x） */
    var scale by remember { mutableFloatStateOf(1f) }

    /** 图片在 X 轴的平移量（像素，仅在 scale > 1 时有效） */
    var offsetX by remember { mutableFloatStateOf(0f) }

    /** 图片在 Y 轴的平移量（像素，仅在 scale > 1 时有效） */
    var offsetY by remember { mutableFloatStateOf(0f) }

    /** 下滑退出的偏移量（像素，正值 = 向下） */
    var dismissOffsetY by remember { mutableFloatStateOf(0f) }

    /**
     * 背景透明度：随下滑距离线性降低
     * 下滑 500px 时完全透明，营造"图片消失"的视觉效果
     */
    val backgroundAlpha by remember {
        derivedStateOf { (1f - abs(dismissOffsetY) / 500f).coerceIn(0f, 1f) }
    }

    /** 顶部栏可见性（单击切换） */
    var showBars by remember { mutableStateOf(true) }

    // ================================================================
    // 返回键处理
    // 已放大时：先弹性缩回，不立即退出（与系统相册行为一致）
    // 正常比例时：直接退出
    // ================================================================
    BackHandler {
        if (scale > 1f) {
            coroutineScope.launch {
                animate(scale, 1f, animationSpec = spring()) { v, _ -> scale = v }
                offsetX = 0f
                offsetY = 0f
            }
        } else {
            onNavigateBack()
        }
    }

    // ================================================================
    // 根布局：纯黑背景（透明度随下滑变化）
    // ================================================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha))
            .statusBarsPadding()
    ) {

        // ============================================================
        // 内容区：根据媒体类型切换图片查看器 / 视频播放器
        // ============================================================
        if (mediaType == MediaType.VIDEO) {
            // ---- 视频播放器（Media3 ExoPlayer，进入自动播放）----
            VideoPlayer(
                uri      = uri,
                modifier = Modifier
                    .fillMaxSize()
                    .then(sharedModifier)  // 共享元素过渡
            )
        } else {
        // ---- 图片查看器（支持缩放、平移、下滑返回）----
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(uri)
                .build(),
            contentDescription = fileName,
            contentScale = ContentScale.Fit,       // Fit = 保持比例完整显示
            modifier = Modifier
                .fillMaxSize()
                .then(sharedModifier)  // 共享元素过渡（从缩略图飞入详情页）
                // graphicsLayer 在 GPU 层做变换，不触发重新布局，确保流畅
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY + dismissOffsetY  // 平移 + 退出偏移叠加
                )

                // --------------------------------------------------
                // 手势层 ①：缩放 + 平移 + 下滑触发
                // detectTransformGestures 同时处理：
                //   - 双指捏合/张开 → zoom 不为 1f
                //   - 单指或双指拖动 → pan 有偏移
                // --------------------------------------------------
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale

                        if (newScale > 1.01f) {
                            // ---- 已放大模式：平移图片，限制在边界内 ----
                            // 边界 = 图片超出屏幕的一半
                            val boundX = (size.width  * (newScale - 1f)) / 2f
                            val boundY = (size.height * (newScale - 1f)) / 2f
                            offsetX = (offsetX + pan.x).coerceIn(-boundX, boundX)
                            offsetY = (offsetY + pan.y).coerceIn(-boundY, boundY)
                        } else {
                            // ---- 正常比例模式：纵向拖动累加到退出偏移 ----
                            scale = 1f        // 防止浮点误差导致 scale 卡在 1.001f
                            offsetX = 0f
                            offsetY = 0f
                            dismissOffsetY += pan.y
                        }
                    }
                }

                // --------------------------------------------------
                // 手势层 ②：手势结束检测（退出 or 弹回）
                // 独立的 pointerInput，使用 requireUnconsumed = false
                // 确保即使 ① 消费了 DOWN 事件，此处仍能监听到 UP 事件
                // --------------------------------------------------
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        waitForUpOrCancellation()   // 等待所有手指抬起

                        // 手势结束时判断是否触发退出
                        if (abs(dismissOffsetY) > 0.5f) {
                            if (abs(dismissOffsetY) > 180f) {
                                // 拖动超过 180px → 退出
                                onNavigateBack()
                            } else {
                                // 拖动不足 → 弹性回弹到原位
                                val startY = dismissOffsetY
                                coroutineScope.launch {
                                    animate(
                                        initialValue = startY,
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    ) { v, _ -> dismissOffsetY = v }
                                }
                            }
                        }
                    }
                }

                // --------------------------------------------------
                // 手势层 ③：单击 + 双击
                // detectTapGestures 与 detectTransformGestures 可共存：
                //   - 拖动/捏合时不会触发 onTap/onDoubleTap
                //   - 短暂点击时两者都响应，但 ① 的 pan/zoom=0 无副作用
                // --------------------------------------------------
                .pointerInput(Unit) {
                    detectTapGestures(
                        // 单击：切换顶部栏
                        onTap = { showBars = !showBars },

                        // 双击：放大到 2.5x 或缩回 1x（带弹簧动画）
                        onDoubleTap = {
                            coroutineScope.launch {
                                if (scale > 1f) {
                                    // 已放大 → 还原
                                    animate(
                                        initialValue = scale,
                                        targetValue = 1f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMedium)
                                    ) { v, _ -> scale = v }
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    // 正常大小 → 放大 2.5x
                                    animate(
                                        initialValue = 1f,
                                        targetValue = 2.5f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMedium)
                                    ) { v, _ -> scale = v }
                                }
                            }
                        }
                    )
                }
        )
        } // end else（图片查看器）

        // ============================================================
        // 顶部栏：返回按钮 + 文件名
        // 单击图片时淡入/淡出 + 从顶部滑入/滑出
        // ============================================================
        AnimatedVisibility(
            visible = showBars,
            enter = fadeIn(tween(150)) + slideInVertically { -it },
            exit  = fadeOut(tween(150)) + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 渐变蒙层：保证白色图标在任意背景下可见
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回按钮
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                // 文件名（超长时截断）
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ============================================================
// 视频播放器（私有组件）
// ============================================================

/**
 * 基于 Media3 ExoPlayer 的视频播放组件
 *
 * - 进入即自动播放（playWhenReady = true）
 * - 显示内置控制条（进度条、播放/暂停按钮）
 * - Composable 退出组合时自动释放播放器资源，防止内存和解码器泄漏
 *
 * @param uri      视频内容 URI（content://media/...）
 * @param modifier 包含共享元素过渡和尺寸约束的修饰符
 */
@Composable
private fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 以 uri 为 key：URI 变化时重建播放器实例
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3MediaItem.fromUri(uri))  // 绑定视频 URI
            prepare()                                    // 开始缓冲
            playWhenReady = true                         // 缓冲完成后自动播放
        }
    }

    // Composable 离开组合时释放播放器，避免音频和解码器资源泄漏
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // AndroidView：将 View 体系的 PlayerView 桥接到 Compose
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true   // 显示内置播放控制条
            }
        },
        modifier = modifier
    )
}
