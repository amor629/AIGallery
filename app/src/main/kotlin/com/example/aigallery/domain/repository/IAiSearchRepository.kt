package com.example.aigallery.domain.repository

import com.example.aigallery.domain.model.SearchCriteria

/**
 * AI 自然语言检索解析接口（Domain 层抽象）
 *
 * 职责：将用户输入的自然语言查询文本，通过 LLM 解析为结构化的 [SearchCriteria]，
 * 再由调用方在本地媒体列表上应用过滤。
 *
 * ⚠️ 隐私安全约束：
 * - 此接口及其实现**绝对不上传任何图片**到 AI 服务
 * - 发给 LLM 的仅有：当前时间 + 用户输入的文字查询
 * - 完整的媒体过滤在本地设备上完成
 *
 * ⚠️ 异常约束：
 * - 实现类必须捕获所有异常，以 [Result] 形式返回，不允许向上抛
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
}
