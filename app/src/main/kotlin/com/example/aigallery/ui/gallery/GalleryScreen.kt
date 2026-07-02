package com.example.aigallery.ui.gallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade  // Coil3 3.x：crossfade 扩展函数位于 coil3.request
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.domain.model.TimelineItem
import com.example.aigallery.ui.LocalAnimatedVisibilityScope
import com.example.aigallery.ui.LocalSharedTransitionScope
import com.example.aigallery.ui.gallery.GalleryViewModel.SearchUiState
import java.util.Locale
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxHeight

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
// 权限状态密封类
// ============================================================

/**
 * 媒体权限的三种状态，决定显示什么 UI
 *
 * - Granted：已授权，正常展示相册
 * - Denied：已拒绝，但 Android 仍允许再次弹窗请求
 * - PermanentlyDenied：用户勾选了"不再询问"，Android 不会再弹系统权限框，
 *     必须引导用户手动进入系统设置开启
 */
private sealed interface PermissionStatus {
    data object Granted           : PermissionStatus
    data object Denied            : PermissionStatus   // 可再次请求
    data object PermanentlyDenied : PermissionStatus   // 必须去系统设置
}

/**
 * 计算当前媒体权限状态
 *
 * 判断逻辑（Android 官方推荐）：
 * 1. 检查 READ_MEDIA_IMAGES 或 READ_MEDIA_VISUAL_USER_SELECTED 是否已授权
 * 2. 若均未授权：
 *    - shouldShowRequestPermissionRationale == true  → 用户曾拒绝过，还可再次请求
 *    - shouldShowRequestPermissionRationale == false → 要么从未请求，要么永久拒绝
 *      区分这两者需结合 [hasEverAskedPermission] 标志：
 *      - 从未请求过：返回 Denied（触发首次请求）
 *      - 已请求过但 rationale=false：返回 PermanentlyDenied（去系统设置）
 *
 * @param context              应用 Context（用于 checkSelfPermission）
 * @param activity             当前 Activity（用于 shouldShowRequestPermissionRationale）
 * @param hasEverAskedPermission 是否曾经发起过权限请求（由调用方跟踪）
 */
private fun resolvePermissionStatus(
    context: android.content.Context,
    activity: Activity?,
    hasEverAskedPermission: Boolean
): PermissionStatus {
    // 图片权限 或 部分授权（二选一即可）
    val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED

    if (granted) return PermissionStatus.Granted

    // 权限未授权：判断是否还可以弹请求框
    val canShowRationale = activity?.shouldShowRequestPermissionRationale(
        Manifest.permission.READ_MEDIA_IMAGES
    ) == true

    return when {
        canShowRationale    -> PermissionStatus.Denied             // 曾拒绝，仍可再次请求
        hasEverAskedPermission -> PermissionStatus.PermanentlyDenied // 请求过 + rationale=false = 永久拒绝
        else                -> PermissionStatus.Denied             // 从未请求，触发首次请求
    }
}

/**
 * 判断当前是否为「局部授权」模式
 *
 * Android 14 引入的「选择照片」权限：
 * - 用户选择「仅授权部分照片」→ READ_MEDIA_VISUAL_USER_SELECTED 已授权，READ_MEDIA_IMAGES 未授权
 * - 此时 MediaStore 只返回用户勾选的那些照片，其他文件夹（下载、微信等）均不可见
 */
private fun isPartialMediaAccess(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) !=
            PackageManager.PERMISSION_GRANTED &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED

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
    onNavigateToWaste: () -> Unit = {},        // AI 废片清理入口
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
    // ---- 分类筛选器 ----
    val currentFilter by viewModel.currentFilter.collectAsStateWithLifecycle()

    // ---- 搜索状态 ----
    val isSearchActive  by viewModel.isSearchActive.collectAsStateWithLifecycle()
    val searchInput     by viewModel.searchInput.collectAsStateWithLifecycle()
    val searchState     by viewModel.searchState.collectAsStateWithLifecycle()
    // 搜索框自动聚焦器：激活搜索模式时自动弹出键盘
    val searchFocusRequester = remember { FocusRequester() }

    // 搜索模式下拦截系统返回手势/按钮：优先退出搜索而非退出 App
    BackHandler(enabled = isSearchActive) {
        viewModel.deactivateSearch()
    }
    // 多选模式下拦截返回手势：优先退出多选而非退出 App
    BackHandler(enabled = isSelecting) {
        viewModel.clearSelection()
    }


    // ---- 是否显示"确认删除"对话框（在系统弹窗之前的 App 内确认）----
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    // ---- AI 批量删除：双重确认对话框状态 ----
    // 第一步确认（App 内提示）
    var showSearchDeleteDialog1 by remember { mutableStateOf(false) }
    // 第二步确认（强调不可逆）
    var showSearchDeleteDialog2 by remember { mutableStateOf(false) }
    // 待删除的 URI 列表（两步对话框间传递数据）
    var pendingDeleteUris      by remember { mutableStateOf<List<Uri>>(emptyList()) }


    // ---- 权限相关状态 ----

    // 当前 Activity（用于 shouldShowRequestPermissionRationale）
    val activity = context as? Activity

    // 是否曾经发起过权限请求（用于区分"从未请求"和"永久拒绝"）
    var hasEverAskedPermission by remember { mutableStateOf(false) }

    // 计算当前权限状态（初始值）
    var permissionStatus by remember {
        mutableStateOf(
            resolvePermissionStatus(context, activity, hasEverAskedPermission)
        )
    }

    // 是否为「局部授权」（用户选了部分照片而非全量）
    // 局部授权时其他文件夹的图片不可见，需提示用户去设置里改为全量访问
    var isPartialAccess by remember { mutableStateOf(isPartialMediaAccess(context)) }

    // ---- 权限请求 Launcher ----
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 请求完成（无论结果），标记「已请求过」，重新计算状态
        hasEverAskedPermission = true
        permissionStatus = resolvePermissionStatus(context, activity, hasEverAskedPermission)
        isPartialAccess = isPartialMediaAccess(context) // 同步更新局部授权状态
    }

    // ---- 监听 Lifecycle.ON_RESUME：回到前台时刷新权限状态和媒体列表 ----
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val next = resolvePermissionStatus(context, activity, hasEverAskedPermission)
                permissionStatus = next
                isPartialAccess = isPartialMediaAccess(context)
                // 每次回到前台（含后台新增文件、从系统设置授权后返回）都主动刷新
                // ContentObserver 在 App 后台超过 5s 会停止，靠此调用补齐遗漏的文件变化
                if (next == PermissionStatus.Granted) {
                    viewModel.refreshMedia()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- 首次进入：若无权限且从未请求过，自动弹出系统权限框 ----
    LaunchedEffect(Unit) {
        if (permissionStatus == PermissionStatus.Denied && !hasEverAskedPermission) {
            hasEverAskedPermission = true
            permissionLauncher.launch(MEDIA_PERMISSIONS)
        }
    }
    // ---- 系统删除确认弹窗 Launcher（Android 10+ 必须）----
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { _ ->
        // 无论用户确认还是取消，都退出多选模式（列表会由 MediaStore Flow 自动刷新）
        viewModel.deactivateSearch()   // 搜索批删完成后同时退出搜索模式
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

    // ---- AI 搜索批删：第一步确认（App 内提示，告知将删除数量）----
    if (showSearchDeleteDialog1) {
        AlertDialog(
            onDismissRequest = { showSearchDeleteDialog1 = false },
            title = { Text("批量删除确认") },
            text = {
                Text(
                    "AI 为您找到 ${pendingDeleteUris.size} 张相关图片。\n\n" +
                    "确定要删除全部结果吗？此操作无法撤销。"
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    showSearchDeleteDialog1 = false
                    showSearchDeleteDialog2 = true   // 进入第二步确认
                }) { Text("下一步") }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDeleteDialog1 = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ---- AI 搜索批删：第二步确认（强调不可逆，使用危险色按钮）----
    if (showSearchDeleteDialog2) {
        AlertDialog(
            onDismissRequest = { showSearchDeleteDialog2 = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("最终确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            text = {
                Text(
                    "即将永久删除 ${pendingDeleteUris.size} 张图片，\n" +
                    "删除后无法恢复，请确认继续。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSearchDeleteDialog2 = false
                        viewModel.deleteMediaDirect(pendingDeleteUris)  // 发起系统删除
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("永久删除", color = MaterialTheme.colorScheme.onError) }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDeleteDialog2 = false }) {
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
                // ---- 普通模式顶部栏 / 搜索模式顶部栏 ----
                if (isSearchActive) {
                    // 搜索模式：顶栏替换为搜索输入框
                    TopAppBar(
                        navigationIcon = {
                            // 关闭搜索，退回普通模式
                            IconButton(onClick = viewModel::deactivateSearch) {
                                Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                            }
                        },
                        title = {
                            // 搜索输入框（单行，自动聚焦，回车触发搜索）
                            OutlinedTextField(
                                value         = searchInput,
                                onValueChange = viewModel::onSearchInputChanged,
                                placeholder   = { Text("搜索相册…") },
                                singleLine    = true,
                                modifier      = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = { viewModel.performSearch(searchInput) }
                                ),
                            )
                        },
                        actions = {
                            // AI 批量删除按钮（仅在搜索有结果时显示）
                            val searchResults = (searchState as? GalleryViewModel.SearchUiState.Success)?.results
                            if (!searchResults.isNullOrEmpty()) {
                                IconButton(
                                    onClick = {
                                        pendingDeleteUris = searchResults.map { it.uri }
                                        showSearchDeleteDialog1 = true
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "批量删除搜索结果",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            // 点击搜索按钮提交查询
                            IconButton(
                                onClick  = { viewModel.performSearch(searchInput) },
                                enabled  = searchInput.isNotBlank()
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "搜索")
                            }
                        }
                    )
                    // 激活搜索模式后立即请求键盘焦点
                    LaunchedEffect(isSearchActive) {
                        if (isSearchActive) searchFocusRequester.requestFocus()
                    }
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
                            // AI 废片清理按钮
                            IconButton(onClick = onNavigateToWaste) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "AI 废片清理",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            // 搜索按钮（仅在已有内容时可用）
                            if (uiState is GalleryUiState.Success) {
                                IconButton(onClick = viewModel::activateSearch) {
                                    Icon(Icons.Default.Search, contentDescription = "搜索")
                                }
                            }
                            IconButton(onClick = onNavigateToSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "设置")
                            }
                        },
                        scrollBehavior = scrollBehavior
                    )
                }
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

        // ---- 权限未授权：显示权限说明页（区分"可再次请求"和"已永久拒绝"）----
        if (permissionStatus != PermissionStatus.Granted) {
            PermissionRequiredContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                isPermanentlyDenied = permissionStatus == PermissionStatus.PermanentlyDenied,
                onRequestPermission = {
                    hasEverAskedPermission = true
                    permissionLauncher.launch(MEDIA_PERMISSIONS)
                },
                onOpenSettings = {
                    // 永久拒绝时：跳转到 App 系统设置页（用户手动开权限）
                    val settingsIntent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(settingsIntent)
                }
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // 局部授权时：优先提示用户如何开启全量访问
                    if (isPartialAccess) {
                        PartialAccessBanner(
                            onOpenSettings = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                            }
                        )
                    }
                    // 分类筛选 Chip 行（空中展示，方便用户切换回全部）
                    FilterChipRow(
                        currentFilter    = currentFilter,
                        onFilterSelected = viewModel::setFilter
                    )
                    EmptyContent(modifier = Modifier.weight(1f))
                }
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
                    isPermanentlyDenied = permissionStatus == PermissionStatus.PermanentlyDenied,
                    onRequestPermission = {
                        hasEverAskedPermission = true
                        permissionLauncher.launch(MEDIA_PERMISSIONS)
                    },
                    onOpenSettings = {
                        val settingsIntent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(settingsIntent)
                    }
                )
            }

            is GalleryUiState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // 局部授权提示横幅（仅部分照片可见时显示）
                    if (isPartialAccess) {
                        PartialAccessBanner(
                            onOpenSettings = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                            }
                        )
                    }

                    // 搜索激活时：隐藏 FilterChip，展示搜索结果
                    if (isSearchActive) {
                        // 搜索结果内容区
                        when (val state = searchState) {
                            is SearchUiState.Idle -> {
                                // 已进入搜索模式但尚未提交查询：显示提示文字
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "输入关键词，\n按回车或点击搜索",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            is SearchUiState.Loading -> {
                                // AI 正在解析查询中
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator()
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "AI 正在理解您的查询…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            is SearchUiState.VisualSearching -> {
                                // 视觉扫描进行中：顶部进度信息 + 下方实时结果
                                val vs = state
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // 进度横幅
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.size(8.dp))
                                        Text(
                                            text = "正在识别图片内容 (${vs.scanned}/${vs.total})，" +
                                                   "已找到 ${vs.found} 张",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    // 实时结果网格
                                    if (vs.partialResults.isNotEmpty()) {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(3),
                                            contentPadding = PaddingValues(1.dp),
                                            modifier = Modifier.fillMaxHeight()
                                        ) {
                                            items(vs.partialResults.size) { index ->
                                                val item = vs.partialResults[index]
                                                MediaThumbnailItem(
                                                    media       = item,
                                                    isSelecting = false,
                                                    isSelected  = false,
                                                    onClick     = { onNavigateToDetail(item) },
                                                    onLongClick = {}
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is SearchUiState.Success -> {
                                // 有搜索结果：展示扁平媒体网格（无时间轴分组）
                                val results = state.results
                                Text(
                                    text = "共找到 ${results.size} 个结果",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    contentPadding = PaddingValues(1.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(results.size) { index ->
                                        val item = results[index]
                                        // 搜索结果支持多选：长按进入多选模式，单击切换选中/查看
                                        MediaThumbnailItem(
                                            media       = item,
                                            isSelecting = isSelecting,
                                            isSelected  = item.uri in selectedUris,
                                            onClick     = {
                                                if (isSelecting) viewModel.toggleSelection(item.uri)
                                                else onNavigateToDetail(item)
                                            },
                                            onLongClick = { viewModel.enterSelectionMode(item.uri) }
                                        )
                                    }
                                }
                            }
                            is SearchUiState.Empty -> {
                                // 无搜索结果
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector  = Icons.Default.Search,
                                            contentDescription = null,
                                            modifier     = Modifier.size(64.dp),
                                            tint         = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text  = "没有找到相关内容",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // 正常模式：分类筛选 Chip + 时间轴网格
                        FilterChipRow(
                            currentFilter    = currentFilter,
                            onFilterSelected = viewModel::setFilter
                        )
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
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
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

/**
 * 权限未授权说明页
 *
 * 两种场景：
 * - [isPermanentlyDenied] = false：首次请求或可再次请求 → 显示"授权访问相册"按钮
 * - [isPermanentlyDenied] = true ：用户勾选"不再询问" → 显示"前往系统设置"按钮，
 *                                  并用卡片说明如何手动开启权限
 *
 * @param isPermanentlyDenied 权限是否已被永久拒绝
 * @param onRequestPermission 发起系统权限请求的回调
 * @param onOpenSettings      跳转到系统应用设置页的回调
 */
@Composable
private fun PermissionRequiredContent(
    isPermanentlyDenied: Boolean = false,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit = {},
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
            text = if (isPermanentlyDenied) "相册权限已被禁止" else "需要访问相册权限",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))

        Text(
            text = if (isPermanentlyDenied)
                "您已拒绝授权，请前往系统设置手动开启\n以便 AI Gallery 展示本地相册"
            else
                "AI Gallery 需要读取您的图片和视频\n才能展示本地相册内容",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(24.dp))

        if (isPermanentlyDenied) {
            // ---- 永久拒绝：引导去系统设置 ----

            // 操作步骤说明卡片
            Card(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "如何手动开启权限",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "1. 点击下方「前往系统设置」\n" +
                               "2. 进入「权限」→「照片和视频」\n" +
                               "3. 选择「允许访问所有照片」\n" +
                               "4. 返回 AI Gallery 即可使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // 主操作按钮：跳转系统设置
            Button(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("前往系统设置")
            }
        } else {
            // ---- 首次请求或可再次请求 ----
            Button(onClick = onRequestPermission) {
                Text("授权访问相册")
            }
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
// 分类筛选 Chip 行
// ============================================================

/**
 * 相册分类筛选横向 Chip 行（全部 / 截图 / 视频 / 实况照片）
 *
 * 滚动时也可水平滑动，防止更多类别时溢出屏幕。
 */
@Composable
private fun FilterChipRow(
    currentFilter   : MediaFilter,
    onFilterSelected: (MediaFilter) -> Unit,
    modifier        : Modifier = Modifier
) {
    val options = listOf(
        MediaFilter.All        to "全部",
        MediaFilter.Screenshots to "截图",
        MediaFilter.Videos      to "视频",
    )
    LazyRow(
        modifier           = modifier.fillMaxWidth(),
        contentPadding     = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(options) { (filter, label) ->
            FilterChip(
                selected = currentFilter == filter,
                onClick  = { onFilterSelected(filter) },
                label    = { Text(label) }
            )
        }
    }
}

// ============================================================
// 局部授权提示横幅
// ============================================================

/**
 * 局部授权提示横幅
 *
 * 当用户选择「仅授权部分照片」时显示在相册顶部，说明为何某些文件夹不可见，
 * 并引导用户前往系统设置将照片权限改为「允许访问所有照片」。
 */
@Composable
private fun PartialAccessBanner(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "仅部分照片可见",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                text = "你选择了「仅授权部分照片」，其他文件夹（如下载、微信）中的图片无法显示。" +
                       "前往系统设置 → 权限 → 照片和视频，改为「允许访问所有照片」即可管理全部文件夹。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("去系统设置")
            }
        }
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
