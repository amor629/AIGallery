package com.example.aigallery.ui.gallery

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timelineItems by viewModel.timelineMedia.collectAsStateWithLifecycle()

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
        // 图片权限 或 部分授权，满足其一即可展示相册
        hasPermission = results[Manifest.permission.READ_MEDIA_IMAGES] == true
                || results[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
    }

    // ---- 首次进入：若无权限则自动弹出请求 ----
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(MEDIA_PERMISSIONS)
        }
    }

    // ---- 顶部栏折叠行为（随滚动收缩）----
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AI Gallery",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        // 仅在有数据时显示"共 xxx 张"副标题
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
                    // 设置入口
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                scrollBehavior = scrollBehavior
            )
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
 * 流畅性保证：
 * - [LazyVerticalGrid] 只组合可见 item（屏外内容不渲染）
 * - [AsyncImage] 由 Coil3 管理，仅在 item 可见时加载缩略图，滑出屏幕后释放内存
 * - key 参数让 Compose 精确识别 item 变化，避免不必要的重组
 * - 月份 Header 通过 GridItemSpan(maxLineSpan) 横跨全部列，无需拆分 List
 *
 * @param items 已含月份标题的时间轴列表（由 GalleryViewModel 构建）
 */
@Composable
private fun MediaTimelineGrid(
    items: List<TimelineItem>,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        // 格子间距 2dp，接近系统相册的视觉效果
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
    ) {
        items(
            count = items.size,
            // key：让 Compose 追踪每个 item 的身份，保证动画和滚动位置正确
            key = { index ->
                when (val item = items[index]) {
                    is TimelineItem.Header -> "h_${item.monthKey}"
                    is TimelineItem.Media  -> "m_${item.media.id}"
                }
            },
            // span：Header 横跨全部列，Media 占 1 列
            span = { index ->
                if (items[index] is TimelineItem.Header) GridItemSpan(maxLineSpan)
                else GridItemSpan(1)
            }
        ) { index ->
            when (val item = items[index]) {
                is TimelineItem.Header -> MonthHeaderItem(header = item)
                is TimelineItem.Media  -> MediaThumbnailItem(media = item.media)
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
 * 图片：直接展示 Coil3 加载的缩略图
 * 视频：右下角叠加时长标签（如 "1:23"），左上角叠加播放图标
 *
 * Coil3 缩略图策略：
 * - crossfade(true)：图片加载完成后淡入，避免生硬切换
 * - 不指定 size()：Coil3 根据 Modifier 尺寸自动请求合适分辨率（避免加载全尺寸）
 * - ContentScale.Crop：填满格子，中心裁剪，与系统相册效果一致
 */
@Composable
private fun MediaThumbnailItem(media: MediaItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)            // 强制正方形格子
    ) {
        // ---- 缩略图 ----
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(media.uri)        // content:// URI，Coil3 直接支持
                .crossfade(true)        // 淡入动画
                .build(),
            contentDescription = null,  // 装饰性图片，无障碍不需要描述
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // ---- 视频标识：播放图标（左上角）+ 时长（右下角）----
        if (media.mediaType == MediaType.VIDEO) {
            // 左上角：视频播放图标
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = "视频",
                tint = Color.White,
                modifier = Modifier
                    .padding(4.dp)
                    .size(20.dp)
                    .align(Alignment.TopStart)
            )
            // 右下角：视频时长标签
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
