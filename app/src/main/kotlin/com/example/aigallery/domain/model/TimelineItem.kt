package com.example.aigallery.domain.model

/**
 * 时间轴列表项（密封接口）
 *
 * LazyVerticalGrid 中有两种类型的"行元素"：
 * - [Header]：月份标题行，横跨所有列（GridItemSpan(maxLineSpan)）
 * - [Media]：媒体缩略图格子，占 1 列（GridItemSpan(1)）
 *
 * GalleryViewModel 负责把 List<MediaItem> 转换为 List<TimelineItem>，
 * GalleryScreen 直接渲染此列表，无需再做任何数据处理。
 */
sealed interface TimelineItem {

    /**
     * 月份分组标题
     *
     * @param monthKey  排序/去重用的原始键（如 "2024-12"）
     * @param title     界面展示用的标题（如 "2024年12月"）
     * @param count     本月媒体数量（如 "15张"）
     */
    data class Header(
        val monthKey: String,
        val title: String,
        val count: Int
    ) : TimelineItem

    /**
     * 单个媒体文件格子
     *
     * @param media 媒体元数据（不含像素）
     */
    data class Media(
        val media: MediaItem
    ) : TimelineItem
}
