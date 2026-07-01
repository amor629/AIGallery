package com.example.aigallery.data.mediastore

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.aigallery.domain.model.MediaItem

/**
 * Paging3 数据源：将内存中的 MediaItem 列表分页切片
 *
 * 设计说明：
 * ─────────────────────────────────────────────────────────────
 * 为什么不直接让 PagingSource 查询 MediaStore？
 *   MediaStore 不支持标准的 offset/limit SQL 分页，强行分页需要
 *   把整张表扫描一遍才能定位偏移量，反而更慢。
 *   更好的做法是：
 *   1. MediaStoreRepository 用 Flow 维护完整 MediaItem 列表（只含元数据，很轻量）
 *   2. 本 PagingSource 负责把这份列表"切片"成页，控制 UI 渲染节奏
 *   3. MediaStore 变化时，GalleryViewModel 创建新的 PagingSource 刷新数据
 *
 * 内存安全：
 *   每个 MediaItem ≈ 300 字节纯元数据，1 万张照片 ≈ 3 MB，安全。
 *   Paging3 每次只把当前可见页 + 预取页的 MediaItem 交给 Compose 渲染，
 *   图片像素由 Coil3 在 UI 层按需加载、按需释放，不在此处管理。
 *
 * @param mediaItems 完整媒体列表（来自 MediaStoreRepository，已按时间降序排好）
 */
class MediaPagingSource(
    private val mediaItems: List<MediaItem>
) : PagingSource<Int, MediaItem>() {

    /**
     * 核心加载方法：根据页码返回对应的数据切片
     *
     * @param params.key      页码（null 表示第一页，从 0 开始）
     * @param params.loadSize 每次加载的条目数（由 PagingConfig.pageSize 决定）
     */
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaItem> {
        // 当前页码，首次加载时为 null，默认第 0 页
        val page = params.key ?: 0
        val pageSize = params.loadSize
        // 当前页在列表中的起始下标
        val startIndex = page * pageSize

        return try {
            // 安全切片：coerceIn 防止下标越界
            val endIndex = (startIndex + pageSize).coerceAtMost(mediaItems.size)
            val items = if (startIndex >= mediaItems.size) {
                emptyList()
            } else {
                mediaItems.subList(startIndex, endIndex)
            }

            LoadResult.Page(
                data = items,
                // 第一页没有上一页
                prevKey = if (page == 0) null else page - 1,
                // 已到末尾则没有下一页
                nextKey = if (endIndex >= mediaItems.size) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * 数据刷新后，计算应该从哪个 key（页码）重新加载
     * 让 Paging3 尽量保持当前滚动位置附近的数据
     */
    override fun getRefreshKey(state: PagingState<Int, MediaItem>): Int? {
        // anchorPosition 是用户当前可见区域中心的 item 位置
        return state.anchorPosition?.let { anchor ->
            val anchorPage = state.closestPageToPosition(anchor)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
