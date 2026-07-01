package com.example.aigallery.ui.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade  // Coil3 3.x：crossfade 扩展函数位于 coil3.request
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.domain.model.TimelineItem
import com.example.aigallery.ui.LocalAnimatedVisibilityScope
import com.example.aigallery.ui.LocalSharedTransitionScope
import java.util.Locale

// ============================================================
// 所需权限（minSdk=34，直接用 Android 14 权限模型）
// ============================================================
private val MEDIA_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    // Android 14+：用户可选择部分授权，授权此权限后 MediaStore 返回用户选中的媒体
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
)

// ============================================================
// 主页入口
// ============================================================

/**
 * 相册主页
 *
 * 职责：
 * 1. 运行时申请媒体权限
 * 2. 根据 [GalleryUiState] 展示不同内容（加载中 / 空 / 网格 / 错误）
 * 3. 渲染时间轴分组的媒体瀑布流
 *
 * @param onNavigateToSettings 跳转到设置页的回调（由 NavHost 提供）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToDetail: (MediaItem) -> Unit = {},  // 点击缩略图进入详情页
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timelineItems by viewModel.timelineMedia.collectAsStateWithLifecycle()

    // ---- 多选模式状态 ----
    val isSelecting by viewModel.isSelecting.collectAsStateWithLifecycle()
    val selectedUris by viewModel.selectedUris.collectAsStateWithLifecycle()
    val deleteRequest by viewModel.deleteRequest.collectAsStateWithLifecycle()

    // ---- 是否显示"确认删除"对话框（在系统弹窗之前的 App 内确认）----
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // ---- 权限状态（检查图片或部分授权，两者满足其一即可）----
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // ---- 权限请求 Launcher ----
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermission = results[Manifest.permission.READ_MEDIA_IMAGES] == true
                || results[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
    }

    // ---- 系统删除确认弹窗 Launcher（Android 10+ 必须）----
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { _ ->
        // 无论用户确认还是取消，都退出多选模式（列表会由 MediaStore Flow 自动刷新）
        viewModel.clearSelection()
        viewModel.clearDeleteRequest()
    }

    // ---- 收到系统删除请求时，启动系统弹窗 ----
    LaunchedEffect(deleteRequest) {
        deleteRequest?.let { sender ->
            deleteLauncher.launch(
                IntentSenderRequest.Builder(sender).build()
            )
        }
    }

    // ---- 首次进入：若无权限则自动弹出请求 ----
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(MEDIA_PERMISSIONS)
        }
    }

    // ---- App 内确认删除对话框（系统弹窗前的二次确认）----
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("确认删除") },
            text = {
                Text(
                    "即将永久删除已选中的 ${selectedUris.size} 个文件，\n" +
                    "此操作不可恢复，确定继续吗？"
                )
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.requestDeleteSelected()  // 发起系统级删除请求
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ---- 顶部栏折叠行为（随滚动收缩）----
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (isSelecting) {
                // ---- 多选模式顶部栏：显示已选数量 + 全选 + 退出 ----
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出多选")
                        }
                    },
                    title = {
                        Text(
                            text = if (selectedUris.isEmpty()) "请选择"
                                   else "已选 ${selectedUris.size} 项",
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    actions = {
                        // 全选 / 取消全选按钮
                        IconButton(onClick = viewModel::toggleSelectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选")
                        }
                    }
                )
            } else {
                // ---- 普通模式顶部栏 ----
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "AI Gallery",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (uiState is GalleryUiState.Success) {
                                val count = (uiState as GalleryUiState.Success).totalCount
                                Text(
                                    text = "共 ${"%,d".format(count)} 张",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        // ---- 多选模式底部操作栏 ----
        bottomBar = {
            AnimatedVisibility(
                visible = isSelecting,
                enter = fadeIn(),
                exit  = fadeOut()
            ) {
                BottomAppBar(
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    // 分享按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // ---- 分享 ----
                        IconButton(
                            onClick = {
                                // 构建多文件分享 Intent
                                val uriList = ArrayList(selectedUris.toList())
                                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "*/*"                     // 混合图片+视频用 */*
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "分享至")
                                )
                            },
                            enabled = selectedUris.isNotEmpty()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Text(
                                    text = "分享",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        // ---- 删除 ----
                        IconButton(
                            onClick = { showDeleteConfirmDialog = true },
                            enabled = selectedUris.isNotEmpty()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = if (selectedUris.isNotEmpty())
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                                Text(
                                    text = "删除",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedUris.isNotEmpty())
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->

        // ---- 权限未授权：显示权限说明页 ----
        if (!hasPermission) {
            PermissionRequiredContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                onRequestPermission = { permissionLauncher.launch(MEDIA_PERMISSIONS) }
            )
            return@Scaffold
        }

        // ---- 根据状态切换内容 ----
        when (uiState) {
            is GalleryUiState.Loading -> {
                LoadingContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }

            is GalleryUiState.Empty -> {
                EmptyContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }

            is GalleryUiState.Error -> {
                ErrorContent(
                    message = (uiState as GalleryUiState.Error).message,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }

            is GalleryUiState.PermissionDenied -> {
                PermissionRequiredContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    onRequestPermission = { permissionLauncher.launch(MEDIA_PERMISSIONS) }
                )
            }

            is GalleryUiState.Success -> {
                // ✅ 正常状态：展示时间轴媒体网格
                MediaTimelineGrid(
                    items = timelineItems,
                    isSelecting = isSelecting,
                    selectedUris = selectedUris,
                    onItemClick = { media ->
                        if (isSelecting) {
                            // 多选模式：单击切换选中状态
                            viewModel.toggleSelection(media.uri)
                        } else {
                            // 普通模式：进入详情页
                            onNavigateToDetail(media)
                        }
                    },
                    onItemLongClick = { media ->
                        // 长按：进入多选模式，同时选中此项
                        viewModel.enterSelectionMode(media.uri)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }
        }
    }
}

// ============================================================
// 核心组件：时间轴媒体网格
// ============================================================

/**
 * 带月份分组标题的 3 列媒体瀑布流
 *
 * @param items         已含月份标题的时间轴列表
 * @param isSelecting   是否处于多选模式
 * @param selectedUris  已选中的 URI 集合
 * @param onItemClick   单击回调（多选模式下切换选中；普通模式下导航到详情页）
 * @param onItemLongClick 长按回调（进入多选模式）
 */
@Composable
private fun MediaTimelineGrid(
    items: List<TimelineItem>,
    isSelecting: Boolean = false,
    selectedUris: Set<Uri> = emptySet(),
    onItemClick: (MediaItem) -> Unit = {},
    onItemLongClick: (MediaItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
    ) {
        items(
            count = items.size,
            key = { index ->
                when (val item = items[index]) {
                    is TimelineItem.Header -> "h_${item.monthKey}"
                    is TimelineItem.Media  -> "m_${item.media.id}"
                }
            },
            span = { index ->
                if (items[index] is TimelineItem.Header) GridItemSpan(maxLineSpan)
                else GridItemSpan(1)
            }
        ) { index ->
            when (val item = items[index]) {
                is TimelineItem.Header -> MonthHeaderItem(header = item)
                is TimelineItem.Media  -> MediaThumbnailItem(
                    media = item.media,
                    isSelecting = isSelecting,
                    isSelected = item.media.uri in selectedUris,
                    onClick = { onItemClick(item.media) },
                    onLongClick = { onItemLongClick(item.media) }
                )
            }
        }
    }
}

// ============================================================
// 月份标题行
// ============================================================

@Composable
private fun MonthHeaderItem(header: TimelineItem.Header) {
    Text(
        text = "${header.title}  ·  ${header.count} 张",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
    )
}

// ============================================================
// 单个媒体缩略图格子
// ============================================================

/**
 * 媒体缩略图格子
 *
 * 支持两种交互模式：
 * - 普通模式：单击进入详情页，长按进入多选模式
 * - 多选模式：单击切换选中状态，右上角显示勾选圆圈，已选项有蓝色边框
 *
 * @param media       媒体数据
 * @param isSelecting 是否处于多选模式
 * @param isSelected  当前项是否已被选中
 * @param onClick     单击回调
 * @param onLongClick 长按回调
 */
@OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaThumbnailItem(
    media: MediaItem,
    isSelecting: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val sharedScope = LocalSharedTransitionScope.current
    val visScope    = LocalAnimatedVisibilityScope.current

    // 选中时：图片缩小 + 蓝色边框
    val scale = if (isSelected) 0.88f else 1f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // 多选已选中时添加主题色边框
            .then(
                if (isSelected) Modifier.border(
                    width = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(4.dp)
                ) else Modifier
            )
            // combinedClickable 同时支持单击 + 长按
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // ---- 缩略图（多选模式下轻微缩小，视觉上突出选中边框）----
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(media.uri)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // 多选模式下图片轻微缩放，为边框留出视觉空间
                .then(if (isSelected) Modifier.padding(3.dp) else Modifier)
                .then(
                    if (!isSelecting && sharedScope != null && visScope != null) {
                        // 普通模式才挂载共享元素过渡（多选模式不需要）
                        with(sharedScope) {
                            Modifier.sharedElement(
                                state = rememberSharedContentState("media_${media.uri}"),
                                animatedVisibilityScope = visScope
                            )
                        }
                    } else Modifier
                )
        )

        // ---- 视频标识 ----
        if (media.mediaType == MediaType.VIDEO) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = "视频",
                tint = Color.White,
                modifier = Modifier
                    .padding(4.dp)
                    .size(20.dp)
                    .align(Alignment.TopStart)
            )
            if (media.duration > 0) {
                Text(
                    text = formatDuration(media.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(3.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        // ---- 多选模式：右上角勾选圆圈 ----
        if (isSelecting) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .size(24.dp)
                    .align(Alignment.TopEnd)
            ) {
                if (isSelected) {
                    // 已选中：实心主色勾
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 未选中：空心圆（白底黑边）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.7f))
                            .border(1.5.dp, Color.Gray.copy(alpha = 0.8f), CircleShape)
                    )
                }
            }
        }
    }
}

// ============================================================
// 占位状态：加载中 / 空 / 权限拒绝 / 错误
// ============================================================

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "相册空空如也",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "拍几张照片后回来看看",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun PermissionRequiredContent(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "需要访问相册权限",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "AI Gallery 需要读取您的图片和视频\n才能展示本地相册内容",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, // 居中对齐
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("授权访问相册")
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "加载失败",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
        )
    }
}

// ============================================================
// 工具函数
// ============================================================

/**
 * 视频时长格式化（毫秒 → "分:秒" 或 "时:分:秒"）
 * 例：90500ms → "1:30"，3661000ms → "1:01:01"
 */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours   = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
