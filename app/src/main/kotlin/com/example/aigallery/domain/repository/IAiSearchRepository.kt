package com.example.aigallery.domain.repository

import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.SearchCriteria

/**
 * AI 自然语言检索解析接口（Domain 层抽象）
 *
 * 职责：
 * 1. [parseQuery]：将用户输入的自然语言查询文本，通过 LLM 解析为结构化的 [SearchCriteria]
 * 2. [visualSearchBatch]：对一批图片做视觉内容匹配（判断是否包含 visualQuery 所描述的内容）
 *
 * ⚠️ 隐私安全约束：
 * - [parseQuery] 绝对不上传任何图片，仅传递文字查询
 * - [visualSearchBatch] 上传图片缩略图用于内容识别，需经用户主动触发
 * - 完整的媒体过滤在本地设备上完成
 *
 * ⚠️ 异常约束：
 * - 所有实现类必须捕获异常，以 [Result] 形式返回，不允许向上抛
 */
interface IAiSearchRepository {

    /**
     * 将自然语言查询解析为结构化检索条件
     *
     * @param query       用户输入的自然语言查询文本（例如 "上个月的视频"）
     * @param nowMillis   当前时间毫秒时间戳（用于 AI 计算相对日期，如"昨天"、"上个月"）
     * @return            [Result.success] 包含解析后的 [SearchCriteria]；
     *                    [Result.failure] 包含对用户友好的错误说明
     */
    suspend fun parseQuery(query: String, nowMillis: Long): Result<SearchCriteria>

    /**
     * 对一批图片进行视觉内容匹配（单次 API 调用）
     *
     * 发送最多 [mediaItems].size 张图片（建议 ≤3）到视觉 AI，
     * 询问哪些图片与 [visualQuery] 所描述的内容相关。
     *
     * @param mediaItems  待检测的图片列表（每次调用不超过 3 张以控制 Token 消耗）
     * @param visualQuery 视觉内容描述词（如 "猫咪"、"海边日落"）
     * @return            命中的图片在 [mediaItems] 中的下标列表（0-based）；
     *                    网络/API 失败时返回空列表（不抛异常）
     */
    suspend fun visualSearchBatch(
        mediaItems: List<MediaItem>,
        visualQuery: String,
    ): List<Int>
}
