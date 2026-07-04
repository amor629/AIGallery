package com.example.aigallery.ui.detail

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.example.aigallery.ai.AiState
import com.example.aigallery.domain.model.AiAnalysisResult
import com.example.aigallery.domain.model.AiEditType
import com.example.aigallery.domain.model.AiImageEditResult
import com.example.aigallery.domain.model.LocalEditMode
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.ui.edit.AiEditBottomSheet
import com.example.aigallery.ui.edit.AiEditResultDialog
import com.example.aigallery.ui.edit.PhotoEditBottomBar
import com.example.aigallery.ui.LocalAnimatedVisibilityScope
import com.example.aigallery.ui.LocalSharedTransitionScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 图片/视频详情页（沉浸式全屏 + HorizontalPager 左右滑动切换）
 *
 * 每张图片支持的手势：
 * ┌──────────────────────────────────────────────────────────┐
 * │ scale=1 横向滑  → HorizontalPager 切换上/下一张         │
 * │ 双指捏合        → 缩放（1x ～ 5x）                      │
 * │ 放大后单指拖    → 平移图片（限制在边界内）               │
 * │ 双击            → 放大到 2.5x / 还原到 1x              │
 * │ 单击            → 切换顶部栏显示 / 隐藏                 │
 * │ scale=1 纵向下滑 → 下滑返回（> 180px 触发退出）         │
 * └──────────────────────────────────────────────────────────┘
 *
 * @param allMedia       完整媒体列表（来自 GalleryViewModel，供 Pager 翻页使用）
 * @param initialUri     进入时点击的图片 URI（用于定位初始页）
 * @param onNavigateBack 退出详情页的回调
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoDetailScreen(
    allMedia      : List<MediaItem>,
    initialUri    : Uri,
    onNavigateBack: () -> Unit,
    onNavigateToImageEdit: (Uri, LocalEditMode) -> Unit = { _, _ -> },
    onNavigateToVideoTrim: (Uri, String) -> Unit = { _, _ -> }
) {
    // allMedia 尚未加载完毕时（极少发生），显示加载中状态
    if (allMedia.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
        ) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White
            )
        }
        return
    }

    // ================================================================
    // 共享元素过渡 Scope（由 MainActivity NavHost 通过 CompositionLocal 提供）
    // ================================================================
    val sharedScope = LocalSharedTransitionScope.current
    val visScope    = LocalAnimatedVisibilityScope.current

    // ================================================================
    // AI 识图状态（整个详情页共用一个 ViewModel，切换页面时重置结果）
    // ================================================================
    val aiViewModel    : AiDetailViewModel = hiltViewModel()
    val aiState        by aiViewModel.aiState.collectAsStateWithLifecycle()
    val analysisResult by aiViewModel.analysisResult.collectAsStateWithLifecycle()
    val captionResult  by aiViewModel.captionResult.collectAsStateWithLifecycle()
    val imageEditResult by aiViewModel.imageEditResult.collectAsStateWithLifecycle()
    val writeRequest by aiViewModel.writeRequest.collectAsStateWithLifecycle()
    val deleteRequest by aiViewModel.deleteRequest.collectAsStateWithLifecycle()

    /** 控制"AI 未配置"引导对话框的可见性 */
    var showAiConfigDialog by remember { mutableStateOf(false) }
    var showAiEditSheet by remember { mutableStateOf(false) }

    /** 控制"确认删除"对话框（系统删除授权弹窗之前的 App 内二次确认）的可见性 */
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current
    val toastContext = LocalContext.current

    // ---- "覆盖原图"系统写入授权弹窗 Launcher ----
    val writeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> aiViewModel.onWriteRequestResult(result.resultCode == Activity.RESULT_OK) }

    LaunchedEffect(writeRequest) {
        writeRequest?.let { sender ->
            writeLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    // ---- 删除当前照片的系统授权弹窗 Launcher ----
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        aiViewModel.clearDeleteRequest()
        if (result.resultCode == Activity.RESULT_OK) {
            // 已删除，当前照片不复存在，退出详情页
            onNavigateBack()
        }
    }

    LaunchedEffect(deleteRequest) {
        deleteRequest?.let { sender ->
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    LaunchedEffect(imageEditResult) {
        val ir = imageEditResult
        if (ir is AiImageEditResult.Saved) {
            Toast.makeText(toastContext, ir.message, Toast.LENGTH_SHORT).show()
            aiViewModel.clearImageEditResult()
        }
    }

    /** 顶部栏（返回 + 文件名）可见性，单击图片切换 */
    var showBars by remember { mutableStateOf(true) }

    // ================================================================
    // HorizontalPager 状态
    // ================================================================

    /**
     * 初始页索引：找 initialUri 在列表中的位置，找不到则退到 0。
     * 用 remember 固定，避免 allMedia 后续刷新导致 Pager 跳页。
     */
    val initialIndex = remember(allMedia, initialUri) {
        allMedia.indexOfFirst { it.uri == initialUri }.coerceAtLeast(0)
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount   = { allMedia.size }
    )

    /**
     * 每页的缩放比缓存（key=页面索引, value=缩放比）
     * 用于 HorizontalPager.userScrollEnabled：
     * 当前页 scale > 1 时禁止翻页，防止缩放平移误触翻页。
     */
    val pageScales = remember { mutableStateMapOf<Int, Float>() }
    val currentPageScale = pageScales[pagerState.currentPage] ?: 1f

    /**
     * 当前页的下滑退出偏移（PhotoPageContent 通过回调上报）
     * 用于外层 Box 背景透明度计算，营造"图片消失"效果。
     */
    var dismissOffsetY by remember { mutableFloatStateOf(0f) }

    /** 背景随下滑距离线性变透明（下滑 500px = 完全透明） */
    val backgroundAlpha by remember {
        derivedStateOf { (1f - abs(dismissOffsetY) / 500f).coerceIn(0f, 1f) }
    }

    // 翻页时重置 AI 分析结果 & 下滑偏移量
    LaunchedEffect(pagerState.currentPage) {
        aiViewModel.clearAllOnPageChange()
        dismissOffsetY = 0f
    }

    /** 当前可见页的媒体项（顶部栏标题、AI 按钮类型判断等使用） */
    val currentMedia = allMedia.getOrNull(pagerState.currentPage)

    // ================================================================
    // AI 未配置引导对话框
    // ================================================================
    if (showAiConfigDialog) {
        AlertDialog(
            onDismissRequest = { showAiConfigDialog = false },
            title = { Text("AI 功能未配置") },
            text  = {
                Text(
                    "请点击左上角返回按钮，" +
                    "前往应用内【设置】页填写 API 地址和 Key，" +
                    "配置完成后重新打开照片即可使用 AI 识图。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showAiConfigDialog = false }) { Text("知道了") }
            }
        )
    }

    // ================================================================
    // AI 写文案结果弹窗（成功：展示文案 + 一键复制；失败：提示 + 重试）
    // ================================================================
    when (val cr = captionResult) {
        is AiAnalysisResult.Success -> {
            AlertDialog(
                onDismissRequest = aiViewModel::clearCaption,
                title = {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint               = Color(0xFFFFD700)
                        )
                        Text("AI 朋友圈文案")
                    }
                },
                text = { Text(cr.description, style = MaterialTheme.typography.bodyLarge) },
                confirmButton = {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(cr.description))
                        Toast.makeText(toastContext, "文案已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        aiViewModel.clearCaption()
                    }) { Text("一键复制") }
                },
                dismissButton = {
                    TextButton(onClick = aiViewModel::clearCaption) { Text("关闭") }
                }
            )
        }
        is AiAnalysisResult.Error -> {
            AlertDialog(
                onDismissRequest = aiViewModel::clearCaption,
                title = { Text("文案生成失败") },
                text  = { Text(cr.message) },
                confirmButton = {
                    TextButton(onClick = {
                        currentMedia?.let { aiViewModel.generateCaption(it.uri) }
                    }) { Text("重试") }
                },
                dismissButton = {
                    TextButton(onClick = aiViewModel::clearCaption) { Text("取消") }
                }
            )
        }
        else -> {}
    }

    // ================================================================
    // AI 图片编辑结果弹窗（老照片修复 / AI 美化）
    // 处理完成后需用户选择"另存为新图片"或"覆盖原图"，而非自动保存
    // ================================================================
    when (val ir = imageEditResult) {
        is AiImageEditResult.ReadyToSave -> {
            AiEditResultDialog(
                bitmap = ir.bitmap,
                onSaveAsNew = { aiViewModel.saveAsNew(ir.bitmap, ir.editType) },
                onOverwrite = { aiViewModel.requestOverwrite(ir.sourceUri, ir.bitmap) },
                onDismiss = aiViewModel::clearImageEditResult
            )
        }
        is AiImageEditResult.Error -> {
            AlertDialog(
                onDismissRequest = aiViewModel::clearImageEditResult,
                title = { Text("AI 编辑失败") },
                text  = { Text(ir.message) },
                confirmButton = {
                    TextButton(onClick = aiViewModel::clearImageEditResult) { Text("知道了") }
                }
            )
        }
        else -> {}
    }

    // ================================================================
    // "确认删除"对话框（App 内二次确认，确认后才弹出系统删除授权框）
    // ================================================================
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("确认删除") },
            text  = { Text("即将永久删除这张照片，此操作不可恢复，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = false
                    currentMedia?.let { aiViewModel.requestDelete(it.uri) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("取消") }
            }
        )
    }

    if (showAiEditSheet && currentMedia?.mediaType == MediaType.IMAGE) {
        AiEditBottomSheet(
            onDismiss = { showAiEditSheet = false },
            onSelect = { type ->
                showAiEditSheet = false
                val uri = currentMedia.uri
                when (type) {
                    AiEditType.CAPTION -> {
                        if (aiState is AiState.NotConfigured) showAiConfigDialog = true
                        else aiViewModel.generateCaption(uri)
                    }
                    AiEditType.RECOGNIZE -> {
                        if (aiState is AiState.NotConfigured) showAiConfigDialog = true
                        else aiViewModel.analyzeImage(uri)
                    }
                    AiEditType.RESTORE, AiEditType.BEAUTIFY -> {
                        if (aiState is AiState.NotConfigured) showAiConfigDialog = true
                        else aiViewModel.processImageEdit(uri, type)
                    }
                }
            }
        )
    }

    // AI 文案/识图进行中提示
    if (captionResult is AiAnalysisResult.Loading || analysisResult is AiAnalysisResult.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("AI 处理中") },
            text  = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("正在请求 AI，请稍候…")
                }
            },
            confirmButton = {}
        )
    }

    // AI 图片编辑进行中提示
    if (imageEditResult is AiImageEditResult.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("AI 处理中") },
            text  = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("正在处理图片，请稍候…")
                }
            },
            confirmButton = {}
        )
    }

    // ================================================================
    // 主容器：黑色背景（随下滑淡出）+ Pager + 顶部/底部 UI 叠层
    // ================================================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha))
            .statusBarsPadding()
    ) {

        // ============================================================
        // HorizontalPager：左右滑动切换照片/视频
        //   userScrollEnabled：当前页放大中（scale>1）时禁止翻页
        //   beyondViewportPageCount=2：预渲染前后各 2 张，图片提前解码，无中途卡顿
        //   flingBehavior：tween(200ms) 替代默认弹性 spring，消除翻页停顿感
        // ============================================================
        HorizontalPager(
            state                  = pagerState,
            userScrollEnabled      = currentPageScale <= 1.01f,
            beyondViewportPageCount = 2,
            flingBehavior          = PagerDefaults.flingBehavior(
                state             = pagerState,
                snapAnimationSpec = tween(durationMillis = 200)
            ),
            modifier              = Modifier.fillMaxSize()
        ) { pageIndex ->
            val mediaItem = allMedia.getOrNull(pageIndex) ?: return@HorizontalPager

            // 共享元素过渡：仅初始页（用户点击的那张）生效，其余页直接显示
            val pageSharedModifier: Modifier =
                if (pageIndex == initialIndex && sharedScope != null && visScope != null) {
                    with(sharedScope) {
                        Modifier.sharedElement(
                            state                   = rememberSharedContentState("media_${mediaItem.uri}"),
                            animatedVisibilityScope = visScope
                        )
                    }
                } else Modifier

            PhotoPageContent(
                mediaItem              = mediaItem,
                isCurrentPage          = (pageIndex == pagerState.currentPage),
                sharedModifier         = pageSharedModifier,
                onScaleChanged         = { s -> pageScales[pageIndex] = s },
                onDismissOffsetChanged = { d -> dismissOffsetY = d },
                onNavigateBack         = onNavigateBack,
                onToggleBars           = { showBars = !showBars }
            )
        }

        // ============================================================
        // AI 分析结果面板（底部滑入，Success / Error 时显示）
        // ============================================================
        AnimatedVisibility(
            visible  = analysisResult is AiAnalysisResult.Success
                    || analysisResult is AiAnalysisResult.Error,
            enter    = fadeIn(tween(200)) + slideInVertically { it },
            exit     = fadeOut(tween(200)) + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .padding(horizontal = 12.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 120.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.88f),
                    contentColor   = Color.White
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector        = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint               = Color(0xFFFFD700),
                                modifier           = Modifier.size(18.dp)
                            )
                            Text("AI 识图", style = MaterialTheme.typography.titleSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (analysisResult is AiAnalysisResult.Error) {
                                TextButton(
                                    onClick = {
                                        currentMedia?.let { aiViewModel.analyzeImage(it.uri) }
                                    }
                                ) {
                                    Text("重试", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            IconButton(
                                onClick  = aiViewModel::clearResult,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        when (val r = analysisResult) {
                            is AiAnalysisResult.Success ->
                                Text(r.description, style = MaterialTheme.typography.bodyMedium)
                            is AiAnalysisResult.Error ->
                                Text(
                                    r.message,
                                    color = Color(0xFFFF6B6B),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            else -> {}
                        }
                    }
                }
            }
        }

        // ============================================================
        // 顶部栏（返回按钮 + 文件名）
        // 单击图片时淡入/淡出 + 从顶部滑入/滑出
        // ============================================================
        AnimatedVisibility(
            visible  = showBars,
            enter    = fadeIn(tween(150)) + slideInVertically { -it },
            exit     = fadeOut(tween(150)) + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint               = Color.White
                    )
                }
                Text(
                    text     = currentMedia?.name ?: "",
                    style    = MaterialTheme.typography.titleSmall,
                    color    = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { if (currentMedia != null) showDeleteConfirmDialog = true }
                ) {
                    Icon(
                        imageVector        = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint               = Color.White
                    )
                }
            }
        }

        // ============================================================
        // 底部编辑工具栏（AI 编辑 + 本地编辑 / 视频截取）
        // ============================================================
        AnimatedVisibility(
            visible  = showBars && currentMedia != null,
            enter    = fadeIn(tween(150)) + slideInVertically { it },
            exit     = fadeOut(tween(150)) + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            currentMedia?.let { media ->
                PhotoEditBottomBar(
                    mediaType = media.mediaType,
                    onAiEditClick = {
                        if (media.mediaType == MediaType.IMAGE) showAiEditSheet = true
                    },
                    onLocalEditClick = { mode ->
                        onNavigateToImageEdit(media.uri, mode)
                    },
                    onVideoTrimClick = {
                        onNavigateToVideoTrim(media.uri, media.name)
                    }
                )
            }
        }

    }
}


// =====================================================================
// 私有：单张照片/视频页面内容（被 HorizontalPager 每个 slot 使用）
// =====================================================================

/**
 * 单张媒体的可交互查看页
 *
 * 手势设计（自定义 awaitEachGesture，不使用 detectTransformGestures）：
 *
 *  scale > 1（已放大）
 *    └─ 双指  → 缩放（消费），跟随双指中心点平移
 *    └─ 单指  → 平移（消费），限制边界内
 *
 *  scale = 1（正常）
 *    └─ 双指  → 缩放开始（消费）
 *    └─ 纵向单指拖  → 下滑退出（消费），> 180px 触发 onNavigateBack
 *    └─ 横向单指拖  → 不消费 → HorizontalPager 接管翻页
 *
 * @param mediaItem              当前页媒体信息
 * @param isCurrentPage          是否是 Pager 当前可见页（控制 BackHandler 激活）
 * @param sharedModifier         共享元素 Modifier（仅初始页附加）
 * @param onScaleChanged         缩放变化回调（父级据此设置 HorizontalPager.userScrollEnabled）
 * @param onDismissOffsetChanged 下滑偏移回调（父级据此调整背景透明度）
 * @param onNavigateBack         退出详情页
 * @param onToggleBars           切换顶部栏可见性
 */
@Composable
private fun PhotoPageContent(
    mediaItem             : MediaItem,
    isCurrentPage         : Boolean,
    sharedModifier        : Modifier,
    onScaleChanged        : (Float) -> Unit,
    onDismissOffsetChanged: (Float) -> Unit,
    onNavigateBack        : () -> Unit,
    onToggleBars          : () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    /** 当前缩放比（1f = 原始大小，最大 5x） */
    var scale by remember { mutableFloatStateOf(1f) }

    /** 图片 X 轴平移量（仅 scale>1 时有效） */
    var offsetX by remember { mutableFloatStateOf(0f) }

    /** 图片 Y 轴平移量（仅 scale>1 时有效） */
    var offsetY by remember { mutableFloatStateOf(0f) }

    /** 下滑退出偏移量（由手势更新，动画结束后归零） */
    var dismissOffsetY by remember { mutableFloatStateOf(0f) }

    /**
     * 缩放还原动画 Job 引用（使用 Array 持有，避免触发不必要的重组）
     * 快速连续点击返回时，先取消上一次动画再启动新的，防止多动画并行导致 scale 跳变
     */
    val zoomResetJob = remember { arrayOfNulls<Job>(1) }

    // 当前页且已放大时：返回键缩回到 1x，而不是直接退出详情页
    BackHandler(enabled = isCurrentPage && scale > 1f) {
        zoomResetJob[0]?.cancel()          // 取消上次还在进行中的缩放动画
        zoomResetJob[0] = coroutineScope.launch {
            animate(
                initialValue  = scale,
                targetValue   = 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            ) { v, _ ->
                scale = v
                onScaleChanged(v)
            }
            offsetX = 0f
            offsetY = 0f
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (mediaItem.mediaType == MediaType.VIDEO) {
            // ---- 视频：ExoPlayer 自动播放 ----
            VideoPlayer(
                uri           = mediaItem.uri,
                isCurrentPage = isCurrentPage,
                modifier      = Modifier.fillMaxSize().then(sharedModifier)
            )
        } else {
            // ---- 图片：支持缩放 / 平移 / 下滑退出 ----
            AsyncImage(
                model              = ImageRequest.Builder(LocalContext.current)
                    .data(mediaItem.uri)
                    .build(),
                contentDescription = mediaItem.name,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxSize()
                    .then(sharedModifier)
                    // graphicsLayer：GPU 层做变换，不触发重新布局，确保流畅
                    .graphicsLayer(
                        scaleX       = scale,
                        scaleY       = scale,
                        translationX = offsetX,
                        translationY = offsetY + dismissOffsetY
                    )

                    // --------------------------------------------------
                    // 手势层①：自定义主手势（缩放 / 平移 / 下滑退出）
                    //
                    // 关键：横向事件在 scale=1 时不消费，
                    //       让外层 HorizontalPager 捕获并处理翻页。
                    // --------------------------------------------------
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            // 等待首次触摸（requireUnconsumed=false 避免漏接已消费的 DOWN）
                            awaitFirstDown(requireUnconsumed = false)

                            var prevSpan      = 0f              // 上一帧双指间距
                            var isVertical    : Boolean? = null // 手势方向（null=未确定）
                            var isMultiTouch  = false           // 是否已出现双指
                            var localDismissY = 0f              // 本次手势累计纵向退出距离

                            do {
                                val event   = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }

                                when {
                                    // ========== 双指：捏合缩放 ==========
                                    pressed.size >= 2 -> {
                                        isMultiTouch = true
                                        isVertical   = null  // 多指时重置方向判断

                                        val p1   = pressed[0].position
                                        val p2   = pressed[1].position
                                        val span = (p2 - p1).getDistance()

                                        if (prevSpan > 0f && span > 0f) {
                                            // 根据两指间距变化比值计算缩放因子
                                            val newScale = (scale * (span / prevSpan)).coerceIn(1f, 5f)
                                            scale = newScale
                                            onScaleChanged(newScale)

                                            if (newScale > 1.01f) {
                                                // 跟随双指中心点平移，限制在边界内
                                                val boundX = (size.width  * (newScale - 1f)) / 2f
                                                val boundY = (size.height * (newScale - 1f)) / 2f
                                                val cx  = (p1.x + p2.x) / 2f
                                                val cy  = (p1.y + p2.y) / 2f
                                                val pcx = (pressed[0].previousPosition.x +
                                                           pressed[1].previousPosition.x) / 2f
                                                val pcy = (pressed[0].previousPosition.y +
                                                           pressed[1].previousPosition.y) / 2f
                                                offsetX = (offsetX + (cx - pcx)).coerceIn(-boundX, boundX)
                                                offsetY = (offsetY + (cy - pcy)).coerceIn(-boundY, boundY)
                                            } else {
                                                // 缩回到 1x：清除平移量
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                        }
                                        prevSpan = span
                                        // 双指事件全部消费（阻止 Pager 误判为翻页）
                                        event.changes.forEach { it.consume() }
                                    }

                                    // ========== 单指（非多指后续） ==========
                                    pressed.size == 1 && !isMultiTouch -> {
                                        val change = pressed[0]
                                        // 用 position - previousPosition 计算位移增量，避免外部依赖
                                        val dx = change.position.x - change.previousPosition.x
                                        val dy = change.position.y - change.previousPosition.y

                                        if (scale > 1.01f) {
                                            // 已放大：消费事件，平移图片，限制边界
                                            val boundX = (size.width  * (scale - 1f)) / 2f
                                            val boundY = (size.height * (scale - 1f)) / 2f
                                            offsetX = (offsetX + dx).coerceIn(-boundX, boundX)
                                            offsetY = (offsetY + dy).coerceIn(-boundY, boundY)
                                            change.consume()
                                        } else {
                                            // scale=1：首次超过 8px 时确定方向
                                            if (isVertical == null &&
                                                (abs(dx) > 8f || abs(dy) > 8f)
                                            ) {
                                                // 纵向分量更大 → 下滑退出；否则 → 横向翻页
                                                isVertical = abs(dy) > abs(dx)
                                            }
                                            if (isVertical == true) {
                                                // 纵向：消费事件，累计退出偏移并通知父级
                                                change.consume()
                                                localDismissY += dy
                                                dismissOffsetY = localDismissY
                                                onDismissOffsetChanged(localDismissY)
                                            }
                                            // 横向（isVertical==false）：
                                            //   不消费 → 事件传递给 HorizontalPager 实现翻页
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            // ---- 手势结束：判断退出还是弹回 ----
                            if (!isMultiTouch && isVertical == true) {
                                if (abs(localDismissY) > 180f) {
                                    // 拖动超过 180px → 退出详情页
                                    onNavigateBack()
                                } else {
                                    // 拖动不足 → 弹性回弹到原位
                                    coroutineScope.launch {
                                        animate(
                                            initialValue  = localDismissY,
                                            targetValue   = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness    = Spring.StiffnessMedium
                                            )
                                        ) { v, _ ->
                                            dismissOffsetY = v
                                            onDismissOffsetChanged(v)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // --------------------------------------------------
                    // 手势层②：单击 + 双击
                    //   单击 → 切换顶部栏可见性
                    //   双击 → 放大 2.5x / 还原 1x（弹簧动画）
                    // --------------------------------------------------
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onToggleBars() },
                            onDoubleTap = {
                                coroutineScope.launch {
                                    if (scale > 1f) {
                                        // 已放大 → 还原到 1x
                                        animate(
                                            initialValue  = scale,
                                            targetValue   = 1f,
                                            animationSpec = spring(stiffness = Spring.StiffnessMedium)
                                        ) { v, _ ->
                                            scale = v
                                            onScaleChanged(v)
                                        }
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        // 正常大小 → 放大到 2.5x
                                        animate(
                                            initialValue  = 1f,
                                            targetValue   = 2.5f,
                                            animationSpec = spring(stiffness = Spring.StiffnessMedium)
                                        ) { v, _ ->
                                            scale = v
                                            onScaleChanged(v)
                                        }
                                    }
                                }
                            }
                        )
                    }
            )        }
    }
}


// =====================================================================
// 私有：视频播放器组件（与原版完全相同）
// =====================================================================

/**
 * 基于 Media3 ExoPlayer 的视频播放组件
 *
 * - 进入自动播放，显示内置控制条（进度条 / 播放暂停）
 * - Composable 退出组合时自动释放播放器资源，防止内存/解码器泄漏
 * - remember(uri) 保证翻页到新视频时自动重建播放器实例
 *
 * @param uri      视频内容 URI（content://media/...）
 * @param modifier 包含共享元素过渡和尺寸约束的修饰符
 */
@Composable
private fun VideoPlayer(
    uri          : Uri,
    isCurrentPage: Boolean,     // 是否是当前可见页（false 时静音/暂停）
    modifier     : Modifier = Modifier
) {
    val context = LocalContext.current

    // 以 uri 为 key：URI 变化时重建播放器实例（翻页到新视频时自动触发）
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    /**
     * 当页面不是当前可见页时暂停播放，切回时继续。
     * 解决 beyondViewportPageCount 预渲染导致相邻视频已经开始出声的问题。
     * 使用 LaunchedEffect 在主线程安全地操作 ExoPlayer。
     */
    LaunchedEffect(isCurrentPage) {
        player.playWhenReady = isCurrentPage
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = modifier
    )
}
