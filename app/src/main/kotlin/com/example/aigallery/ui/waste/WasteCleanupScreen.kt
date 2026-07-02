package com.example.aigallery.ui.waste

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.aigallery.domain.model.WastePhoto

/**
 * AI 废片清理页面
 *
 * 状态流转：Idle → Scanning → Done(results) / Error
 * - Idle：显示扫描入口卡片
 * - Scanning：实时进度 + 已发现废片列表
 * - Done(empty)：显示"无废片"成功状态
 * - Done(non-empty)：废片列表 + 全选 + 删除
 * - Error：错误说明 + 重试按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WasteCleanupScreen(
    onNavigateBack: () -> Unit,
    viewModel: WasteCleanupViewModel = hiltViewModel()
) {
    val scanState   by viewModel.scanState.collectAsStateWithLifecycle()
    val selectedUris by viewModel.selectedUris.collectAsStateWithLifecycle()
    val deleteRequest by viewModel.deleteRequest.collectAsStateWithLifecycle()

    // 系统删除确认弹窗 Launcher
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.afterDeletion()   // 从结果列表移除已删除项
        } else {
            viewModel.clearDeleteRequest()
        }
    }

    LaunchedEffect(deleteRequest) {
        deleteRequest?.let { sender ->
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    // 底部操作栏（仅 Done + 有结果时显示）
    val doneResults = (scanState as? WasteCleanupViewModel.ScanState.Done)?.results
        ?.takeIf { it.isNotEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("AI 废片清理") },
                actions = {
                    // Done / Error 状态下显示"重新扫描"按钮
                    if (scanState is WasteCleanupViewModel.ScanState.Done ||
                        scanState is WasteCleanupViewModel.ScanState.Error) {
                        IconButton(onClick = viewModel::startScan) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新扫描")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (doneResults != null) {
                BottomAppBar(modifier = Modifier.navigationBarsPadding()) {
                    val allUris = doneResults.map { it.mediaItem.uri }
                    val allSelected = selectedUris.size == doneResults.size

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 全选 Checkbox
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { viewModel.toggleSelectAll(allUris) }
                        ) {
                            Checkbox(
                                checked = allSelected,
                                onCheckedChange = { viewModel.toggleSelectAll(allUris) }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("全选", style = MaterialTheme.typography.bodyMedium)
                        }

                        // 删除按钮
                        Button(
                            onClick = viewModel::requestDelete,
                            enabled = selectedUris.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("删除选中 (${selectedUris.size})")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = scanState) {
                is WasteCleanupViewModel.ScanState.Idle ->
                    IdleContent(onStartScan = viewModel::startScan)

                is WasteCleanupViewModel.ScanState.Scanning ->
                    ScanningContent(state = state)

                is WasteCleanupViewModel.ScanState.Done ->
                    DoneContent(
                        results      = state.results,
                        selectedUris = selectedUris,
                        onToggle     = { viewModel.toggleSelection(it) }
                    )

                is WasteCleanupViewModel.ScanState.Error ->
                    ErrorContent(
                        message  = state.message,
                        onRetry  = viewModel::startScan
                    )
            }
        }
    }
}

// =====================================================================
// 各状态内容区
// =====================================================================

/** Idle：扫描入口卡片 */
@Composable
private fun IdleContent(onStartScan: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "AI 废片清理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "自动识别相册中的模糊、闭眼、重复和截图照片，帮您快速清理废片，释放存储空间。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onStartScan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("开始 AI 扫描")
                }
            }
        }
    }
}

/** Scanning：进度条 + 已发现废片列表 */
@Composable
private fun ScanningContent(state: WasteCleanupViewModel.ScanState.Scanning) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 进度卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在扫描… (${state.scanned}/${state.total})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.scanned.toFloat() / state.total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.found > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "已发现 ${state.found} 张废片",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        // 实时废片预览
        if (state.partialResults.isNotEmpty()) {
            LazyColumn {
                items(state.partialResults, key = { it.mediaItem.uri.toString() }) { waste ->
                    WastePhotoRow(waste = waste, isSelected = false, onToggle = {})
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Done：废片列表（含空状态） */
@Composable
private fun DoneContent(
    results: List<WastePhoto>,
    selectedUris: Set<android.net.Uri>,
    onToggle: (android.net.Uri) -> Unit
) {
    if (results.isEmpty()) {
        // 空状态：无废片
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "太棒了！没有发现废片",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "相册中的照片质量都不错 👍",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "发现 ${results.size} 张废片",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(results, key = { it.mediaItem.uri.toString() }) { waste ->
                WastePhotoRow(
                    waste      = waste,
                    isSelected = waste.mediaItem.uri in selectedUris,
                    onToggle   = { onToggle(waste.mediaItem.uri) }
                )
                HorizontalDivider()
            }
        }
    }
}

/** Error：错误信息 + 重试 */
@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

// =====================================================================
// 废片行组件
// =====================================================================

@Composable
private fun WastePhotoRow(
    waste: WastePhoto,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩略图
        AsyncImage(
            model              = waste.mediaItem.uri,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        // 文件名 + 废片原因标签
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = waste.mediaItem.name,
                style    = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            ReasonBadge(reason = waste.reason)
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(
            checked         = isSelected,
            onCheckedChange = { onToggle() }
        )
    }
}

/** 废片原因彩色标签（不同类型用不同颜色区分） */
@Composable
private fun ReasonBadge(reason: String) {
    val color = when {
        reason.contains("模糊") -> MaterialTheme.colorScheme.error
        reason.contains("闭眼") -> MaterialTheme.colorScheme.tertiary
        reason.contains("重复") -> MaterialTheme.colorScheme.secondary
        else                    -> MaterialTheme.colorScheme.primary   // 截图 / 其他
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text     = reason,
            style    = MaterialTheme.typography.labelSmall,
            color    = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
