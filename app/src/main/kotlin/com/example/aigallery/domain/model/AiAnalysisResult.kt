package com.example.aigallery.domain.model

/**
 * AI 图片分析结果（密封接口）
 *
 * UI 层穷举处理四种状态，任何状态都不会崩溃或白屏：
 *
 * - [Idle]     初始态，AI 功能入口尚未触发
 * - [Loading]  正在向 AI 服务发请求（UI 显示加载动画）
 * - [Success]  请求成功，[description] 包含 AI 生成的图片描述
 * - [Error]    请求失败，[message] 包含对用户友好的错误说明
 *
 * ⚠️ 架构约束：此模型位于 Domain 层，不允许引用任何 Android 或网络框架类型。
 */
sealed interface AiAnalysisResult {

    /** 初始态：未触发任何 AI 请求 */
    data object Idle : AiAnalysisResult

    /** 加载态：AI 请求正在进行中 */
    data object Loading : AiAnalysisResult

    /**
     * 成功态：AI 返回了有效描述
     * @param description AI 生成的中文图片描述
     */
    data class Success(val description: String) : AiAnalysisResult

    /**
     * 失败态：AI 请求出错
     * @param message 对用户友好的错误说明（不含 API Key 等敏感信息）
     */
    data class Error(val message: String) : AiAnalysisResult
}
