package com.example.aigallery.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.aigallery.data.mediastore.MediaPagingSource
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.TimelineItem
import com.example.aigallery.domain.repository.IMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

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
    // 共享底层数据流（只订阅 MediaStore 一次，避免注册多个 ContentObserver）
    // ----------------------------------------------------------------

    /**
     * 所有媒体的原始列表（含响应式更新）
     *
     * shareIn + replay=1：
     * - [uiState] 和 [pagedMedia] 共享同一个上游 Flow，底层只有一个 ContentObserver
     * - replay=1 确保新订阅者立即收到最新数据，不需要等待下次 MediaStore 变化
     */
    private val allMediaFlow = mediaRepository.getAllMedia()
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            replay = 1
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
