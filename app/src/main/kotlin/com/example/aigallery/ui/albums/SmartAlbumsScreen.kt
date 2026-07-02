package com.example.aigallery.ui.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.aigallery.data.local.db.TagAlbumRow
import com.example.aigallery.ui.smart.SmartAlbumsViewModel
import com.example.aigallery.ui.smart.SmartAlbumsScanState
import com.example.aigallery.domain.model.MediaItem

/**
 * 智能相册页面
 *
 * 两级导航（由 ViewModel 的 selectedTag 驱动，无需额外路由）：
 * - 首页：标签云卡片网格（2列），每张卡片显示封面、标签名、数量
 * - 详情：单个标签下的所有照片（3列网格）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartAlbumsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (MediaItem) -> Unit = {},
    viewModel: SmartAlbumsViewModel = hiltViewModel()
) {
    val tagAlbums   by viewModel.tagAlbums.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val photos      by viewModel.photosForTag.collectAsStateWithLifecycle()
    val scanState   by viewModel.scanState.collectAsStateWithLifecycle()

    BackHandler(enabled = selectedTag != null) { viewModel.clearTag() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedTag != null) viewModel.clearTag() else onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(text = selectedTag ?: "智能相册", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    if (selectedTag == null && scanState !is SmartAlbumsScanState.Scanning) {
                        IconButton(onClick = viewModel::startScan) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新扫描")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (selectedTag == null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 扫描进度条（扫描中或出错时显示）
                    when (val state = scanState) {
                        is SmartAlbumsScanState.Scanning -> ScanningBanner()
                        is SmartAlbumsScanState.Error    -> ErrorBanner(state.message, viewModel::startScan)
                        else -> {}
                    }
                    TagAlbumGrid(
                        albums      = tagAlbums,
                        scanState   = scanState,
                        onTagClick  = viewModel::selectTag,
                        onStartScan = viewModel::startScan
                    )
                }
            } else {
                PhotoGrid(photos = photos, onPhotoClick = onNavigateToDetail)
            }
        }
    }
}

// =====================================================================
// 标签列表（首页）
// =====================================================================

@Composable
private fun TagAlbumGrid(
    albums: List<TagAlbumRow>,
    scanState: SmartAlbumsScanState,
    onTagClick: (String) -> Unit,
    onStartScan: () -> Unit
) {
    if (albums.isEmpty() && scanState !is SmartAlbumsScanState.Scanning) {
        // 空状态：根据扫描状态显示不同提示
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "暂无智能相册",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "点击下方按钮，AI 将自动识别照片场景并分类",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onStartScan) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("开始 AI 扫描分类")
                }
            }
        }
        return
    }
    if (albums.isEmpty()) return  // 扫描中且尚无结果，内容由 ScanningBanner 覆盖

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(albums, key = { it.tag }) { album ->
            TagAlbumCard(album = album, onClick = { onTagClick(album.tag) })
        }
    }
}

@Composable
private fun TagAlbumCard(album: TagAlbumRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 封面图
            AsyncImage(
                model = android.net.Uri.parse(album.coverUri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 渐变遮罩 + 文字
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                        )
                    )
                    .padding(10.dp)
            ) {
                Column {
                    Text(
                        text = album.tag,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${album.count} 张",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

// =====================================================================
// 扫描进度 Banner 和错误 Banner
// =====================================================================

@Composable
private fun ScanningBanner() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(text = "AI 正在后台分析照片场景…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

// =====================================================================
// 单标签照片网格
// =====================================================================

@Composable
private fun PhotoGrid(
    photos: List<MediaItem>,
    onPhotoClick: (MediaItem) -> Unit
) {
    if (photos.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp)
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(1.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(3) }) {
            Text(
                text = "${photos.size} 张照片",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        items(photos, key = { it.uri.toString() }) { photo ->
            AsyncImage(
                model = photo.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(0.dp))
                    .clickable { onPhotoClick(photo) }
                    .padding(0.5.dp)
            )
        }
    }
}


