package com.example.aigallery.domain.model

/**
 * AI 解析后的结构化检索条件（Domain 层模型）
 *
 * 用户输入自然语言后，AI 将其解析为此结构，再在本地媒体列表上过滤。
 * 所有字段均为可选，null 表示不限制该维度。
 *
 * 架构约束：此类位于 Domain 层，禁止引用任何 Android / 网络框架类型。
 */
data class SearchCriteria(
    /** 媒体类型限制（null = 全部） */
    val mediaType: SearchMediaType = SearchMediaType.ALL,

    /**
     * 时间范围下限（毫秒时间戳，含）
     * 用于 "上个月" / "2024年" 等相对/绝对日期查询
     */
    val dateFrom: Long? = null,

    /**
     * 时间范围上限（毫秒时间戳，含）
     */
    val dateTo: Long? = null,

    /**
     * 文件名关键词（模糊匹配，忽略大小写，任意一个命中即可）
     * 例如：["IMG_", "PXL_"] 或 ["screenshot"]
     */
    val filenameKeywords: List<String> = emptyList(),

    /**
     * 相册目录名关键词（模糊匹配，忽略大小写，任意一个命中即可）
     * 例如：["Camera", "微信"] 或 ["Screenshots", "截图"]
     */
    val bucketNameKeywords: List<String> = emptyList(),
)

/**
 * 检索时的媒体类型限制
 */
enum class SearchMediaType {
    /** 图片和视频全部检索 */
    ALL,
    /** 仅检索图片 */
    IMAGE,
    /** 仅检索视频 */
    VIDEO,
}
