package com.example.aigallery.ui.gallery

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.aigallery.data.mediastore.MediaPagingSource
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.domain.model.TimelineItem
import com.example.aigallery.domain.repository.IMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ============================================================
// 媒体筛选器
// ============================================================

/**
 * 相册分类筛选器
 * GalleryScreen 顶部 Chip 使用此枚举控制当前显示的媒体类别
 */
enum class MediaFilter {
    All,          // 全部
    Screenshots,  // 截图
    Videos,       // 视频
    LivePhotos    // 实况照片
}

// ============================================================
// UI 状态定义
// ============================================================

/**
 * 相册主页 UI 状态（密封接口，穷举所有可能情况）
 *
 * GalleryScreen 根据此状态决定显示什么内容：
 * - Loading         → 显示骨架屏或加载动画
 * - Success         → 显示媒体网格，totalCount 用于标题栏
 * - Empty           → 显示空状态插画（"相册空空如也"）
 * - PermissionDenied → 显示权限说明 + 去设置按钮
 * - Error           → 显示错误提示 + 重试按钮
 */
sealed interface GalleryUiState {
    /** 初始加载中（首次启动或切换相册时） */
    data object Loading : GalleryUiState

    /** 加载成功，至少有 1 个媒体文件 */
    data class Success(
        val totalCount: Int      // 媒体总数，显示在标题栏（如"共 1,234 张"）
    ) : GalleryUiState

    /** 媒体库为空（相册中没有图片也没有视频） */
    data object Empty : GalleryUiState

    /** 读取媒体权限被拒绝 */
    data object PermissionDenied : GalleryUiState

    /** 发生不可恢复的错误 */
    data class Error(val message: String) : GalleryUiState
}

// ============================================================
// ViewModel
// ============================================================

/**
 * 相册主页 ViewModel
 *
 * 职责：
 * 1. 从 [IMediaRepository] 获取媒体列表（响应式，MediaStore 变化自动刷新）
 * 2. 将媒体状态封装为 [GalleryUiState] 暴露给 UI
 * 3. 通过 Paging3 将大量媒体切片，防止 OOM
 *
 * 架构约束：
 * - ViewModel 不直接接触 MediaStore / ContentResolver / Cursor
 * - 所有数据通过 IMediaRepository 接口获取
 * - UI 层（GalleryScreen）只调用此 ViewModel，不碰任何数据源
 */
@OptIn(ExperimentalCoroutinesApi::class)  // flatMapLatest 为实验性 API，显式声明使用意图
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val mediaRepository: IMediaRepository
) : ViewModel() {

    // ----------------------------------------------------------------
    // 手动刷新触发器（权限授予后强制重查 MediaStore）
    // ----------------------------------------------------------------

    /**
     * 每次调用 [refreshMedia] 时更新时间戳，驱动 allMediaFlow 重新订阅 getAllMedia()。
     *
     * 背景：ContentObserver 只监听文件新增/删除事件，
     *       用户在系统设置中授予权限不会触发 ContentObserver，
     *       因此需要此机制在权限恢复后主动刷新。
     */
    private val _refreshTrigger = MutableStateFlow(0L)

    /**
     * 通知 ViewModel 立即重新查询 MediaStore。
     *
     * GalleryScreen 在 ON_RESUME 检测到权限从拒绝变为授权时调用此函数。
     */
    fun refreshMedia() {
        _refreshTrigger.value = System.currentTimeMillis()
    }

    // ----------------------------------------------------------------
    // 共享底层数据流（只订阅 MediaStore 一次，避免注册多个 ContentObserver）
    // ----------------------------------------------------------------

    /**
     * 所有媒体的原始列表（含响应式更新）
     *
     * - _refreshTrigger 变化时，flatMapLatest 取消旧 getAllMedia() 订阅（注销旧 ContentObserver），
     *   立即以新订阅重查 MediaStore。
     * - shareIn + replay=1：uiState / timelineMedia 共享同一上游，底层只有一个 ContentObserver。
     */
    private val rawMediaFlow = _refreshTrigger
        .flatMapLatest { mediaRepository.getAllMedia() }
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            replay = 1
        )

    // ---- 当前筛选器状态 ----
    private val _currentFilter = MutableStateFlow(MediaFilter.All)

    /** 当前分类筛选器，GalleryScreen 收集后渲染 Chip 选中状态 */
    val currentFilter: StateFlow<MediaFilter> = _currentFilter.asStateFlow()

    /** 切换筛选类别（由 GalleryScreen FilterChip 点击触发） */
    fun setFilter(filter: MediaFilter) {
        _currentFilter.value = filter
    }

    /**
     * 经筛选后的媒体列表（所有下游 Flow 共享此上游）
     *
     * combine 语义：
     * - rawMediaFlow 发射新列表（拍照、删除）时自动重新过滤
     * - _currentFilter 变化（用户切换 Tab）时自动重新过滤
     */
    private val allMediaFlow = combine(rawMediaFlow, _currentFilter) { list, filter ->
        when (filter) {
            MediaFilter.All         -> list
            MediaFilter.Screenshots -> list.filter { it.isScreenshot }
            MediaFilter.Videos      -> list.filter { it.mediaType == MediaType.VIDEO }
            MediaFilter.LivePhotos  -> list.filter { it.isLivePhoto }
        }
    }.shareIn(
        scope   = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        replay  = 1
    )

    // ----------------------------------------------------------------
    // UI 状态（Loading / Success / Empty / Error）
    // ----------------------------------------------------------------

    /**
     * UI 整体状态 StateFlow
     *
     * GalleryScreen 收集此 Flow 决定顶层 UI（空状态、错误页、正常网格）
     * 初始值为 Loading，等待 MediaStore 首次查询完成
     */
    val uiState: StateFlow<GalleryUiState> = allMediaFlow
        .map { items: List<MediaItem> ->
            when {
                items.isEmpty() -> GalleryUiState.Empty
                else -> GalleryUiState.Success(totalCount = items.size)
            }
        }
        .catch { e ->
            // Flow 发生异常时（如权限突然被撤销），切换到 Error 状态
            emit(GalleryUiState.Error(e.message ?: "读取媒体库失败"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = GalleryUiState.Loading
        )

    // ----------------------------------------------------------------
    // Paging3 分页数据流
    // ----------------------------------------------------------------

    /**
     * 分页媒体数据流（供 GalleryScreen 用 collectAsLazyPagingItems 收集）
     *
     * 分页策略：
     * - pageSize = 60：每页 60 个，3 列网格显示 20 行，适合一屏
     * - prefetchDistance = 60：滑到离底部 60 个时预加载下一页
     * - enablePlaceholders = false：不显示占位格，列表按需增长
     *
     * 刷新策略：
     * - allMediaFlow 发射新列表时（拍照/删除触发），flatMapLatest 取消旧 Pager
     *   并用新数据创建新 PagingSource，列表回到顶部（Phase 1 可接受）
     *
     * cachedIn(viewModelScope)：
     * - 屏幕旋转 / 配置变化时，PagingData 不重新加载，保留已加载页
     */
    val pagedMedia: Flow<PagingData<MediaItem>> = allMediaFlow
        .flatMapLatest { mediaList ->
            Pager(
                config = PagingConfig(
                    pageSize = 60,               // 每页 60 个
                    prefetchDistance = 60,        // 提前预取 60 个，保证滑动流畅
                    enablePlaceholders = false    // 关闭占位符，按需加载
                ),
                // 每次媒体列表变化，用最新数据创建新的 PagingSource
                pagingSourceFactory = { MediaPagingSource(mediaList) }
            ).flow
        }
        .cachedIn(viewModelScope)   // 缓存已加载页，防止旋转屏幕时重新加载

    // ----------------------------------------------------------------
    // 时间轴分组数据流（供 GalleryScreen 的 LazyVerticalGrid 使用）
    // ----------------------------------------------------------------

    /**
     * 将媒体列表转换为含月份标题的时间轴列表
     *
     * 示例结构：
     *   Header("2024年12月", count=15)
     *   Media(item1), Media(item2), ..., Media(item15)
     *   Header("2024年11月", count=42)
     *   Media(item16), ...
     *
     * GalleryScreen 用 LazyVerticalGrid 直接渲染此列表：
     * - Header 项通过 GridItemSpan(maxLineSpan) 横跨全部列
     * - Media 项各占 1 列
     */
    val timelineMedia: StateFlow<List<TimelineItem>> = allMediaFlow
        .map { items -> buildTimeline(items) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ----------------------------------------------------------------
    // 详情页 HorizontalPager 使用的完整媒体列表（公开）
    // ----------------------------------------------------------------

    /**
     * 完整媒体列表（不含分组标题），供 PhotoDetailScreen 的 HorizontalPager 翻页使用。
     * 与 [timelineMedia] 共享同一 [allMediaFlow] 上游，不增加额外的 MediaStore 查询。
     * 详情页通过 navController.getBackStackEntry("gallery") 拿到本 ViewModel 实例后订阅此 Flow。
     */
    val allMedia: StateFlow<List<MediaItem>> = allMediaFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ----------------------------------------------------------------
    // 多选模式状态
    // ----------------------------------------------------------------

    /**
     * 当前是否处于多选模式（长按后进入）
     * true → 底部操作栏可见，缩略图显示选中覆盖层
     */
    private val _isSelecting = MutableStateFlow(false)
    val isSelecting: StateFlow<Boolean> = _isSelecting.asStateFlow()

    /**
     * 当前已选中的媒体 URI 集合（用 Uri 作 key 避免歧义）
     * 空集合 = 未选任何文件（但仍可能处于多选模式）
     */
    private val _selectedUris = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedUris: StateFlow<Set<Uri>> = _selectedUris.asStateFlow()

    /**
     * 一次性事件：当需要启动系统删除确认弹窗时，此 Flow 发射 IntentSender
     *
     * UI 收集到非 null 值后：
     * 1. 启动 IntentSender（系统弹窗）
     * 2. 调用 [clearDeleteRequest] 将此 Flow 清回 null（防止屏幕旋转重复触发）
     */
    private val _deleteRequest = MutableStateFlow<IntentSender?>(null)
    val deleteRequest: StateFlow<IntentSender?> = _deleteRequest.asStateFlow()

    // ----------------------------------------------------------------
    // 多选模式操作
    // ----------------------------------------------------------------

    /**
     * 长按某个媒体 → 进入多选模式，同时将该项置为已选中
     *
     * @param uri 被长按的媒体 URI
     */
    fun enterSelectionMode(uri: Uri) {
        _isSelecting.value = true
        _selectedUris.update { it + uri }
    }

    /**
     * 单击已选中的项 → 取消选中；单击未选中的项 → 选中
     * 仅在多选模式下有效（普通模式下单击由 GalleryScreen 直接导航）
     */
    fun toggleSelection(uri: Uri) {
        _selectedUris.update { current ->
            if (uri in current) current - uri else current + uri
        }
    }

    /**
     * 退出多选模式，清空所有选中项
     * 调用时机：按下返回键、点击取消按钮
     */
    fun clearSelection() {
        _isSelecting.value = false
        _selectedUris.value = emptySet()
    }

    /**
     * 全选 / 取消全选
     * 若当前选中数 < 总数 → 全选；否则 → 取消全选
     */
    fun toggleSelectAll() {
        val allMedia = timelineMedia.value
            .filterIsInstance<TimelineItem.Media>()
            .map { it.media.uri }
            .toSet()
        _selectedUris.update { current ->
            if (current.size < allMedia.size) allMedia else emptySet()
        }
    }

    /**
     * 请求删除当前所有选中的媒体
     *
     * 流程：
     * 1. 收集选中的 URI 列表
     * 2. 调用 Repository 构建系统删除 IntentSender（Android 14 必须经用户授权）
     * 3. 将 IntentSender 推送给 UI，UI 启动系统弹窗
     * 4. 用户确认后系统执行删除，MediaStore Flow 自动刷新列表
     *
     * 注意：此函数只发起请求，不等待用户确认结果。
     *       GalleryScreen 在收到系统回调后调用 [clearSelection] 退出多选模式。
     */
    fun requestDeleteSelected() {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val intentSender = mediaRepository.buildDeleteRequest(uris)
            _deleteRequest.value = intentSender
        }
    }

    /** 系统删除弹窗已启动后，清除请求（防止屏幕旋转重复触发） */
    fun clearDeleteRequest() {
        _deleteRequest.value = null
    }

    // ----------------------------------------------------------------
    // 按月分组辅助方法（供 GalleryScreen Step 3 使用）
    // ----------------------------------------------------------------

    /**
     * 将媒体列表按"年-月"分组，返回有序的月份键列表
     *
     * 示例输出：["2024-12", "2024-11", "2024-10", ...]
     * GalleryScreen 用这个列表渲染时间轴分组标题（"2024年12月"）
     *
     * 注意：此方法供 UI 层在已拿到数据后调用，不是响应式的。
     *      Step 3 会在 GalleryScreen 中结合 LazyPagingItems 使用。
     */
    fun groupByMonth(items: List<MediaItem>): Map<String, List<MediaItem>> {
        return items.groupBy { it.monthKey }
    }

    // ----------------------------------------------------------------
    // 私有：构建时间轴列表（插入月份分组标题）
    // ----------------------------------------------------------------

    private fun buildTimeline(items: List<MediaItem>): List<TimelineItem> {
        if (items.isEmpty()) return emptyList()

        val result = mutableListOf<TimelineItem>()
        // 保持原始顺序（已按时间降序），用 distinct() 保留第一次出现的月份键顺序
        val orderedMonthKeys = items.map { it.monthKey }.distinct()
        // 按月分组（O(n)）
        val groups = items.groupBy { it.monthKey }

        orderedMonthKeys.forEach { monthKey ->
            val monthItems = groups[monthKey] ?: return@forEach
            // 插入月份标题
            result.add(
                TimelineItem.Header(
                    monthKey = monthKey,
                    title = formatMonthTitle(monthKey),
                    count = monthItems.size
                )
            )
            // 插入该月所有媒体
            monthItems.forEach { mediaItem ->
                result.add(TimelineItem.Media(media = mediaItem))
            }
        }

        return result
    }

    /** 将 "2024-12" 格式化为 "2024年12月" */
    private fun formatMonthTitle(monthKey: String): String {
        val parts = monthKey.split("-")
        return if (parts.size == 2) {
            val year = parts[0]
            val month = parts[1].trimStart('0').ifBlank { "0" }
            "${year}年${month}月"
        } else {
            monthKey
        }
    }
}
