package com.example.aigallery.domain.repository

import android.net.Uri
import com.example.aigallery.domain.model.AiAnalysisResult

/**
 * AI 图片分析 Repository 接口（Domain 层抽象）
 *
 * ViewModel 只依赖此接口，不知道网络实现细节（Retrofit / OkHttp 等）。
 * 具体实现在 data 层，通过 Hilt 注入。
 *
 * ⚠️ 架构约束：
 * - 此接口不假设 AI 一定可用，失败状态通过 [AiAnalysisResult.Error] 返回
 * - 调用方无需 try-catch，所有异常在实现类内部处理并转换为 Error 状态
 */
interface IAiImageRepository {

    /**
     * 分析图片内容，返回 AI 生成的中文描述
     *
     * @param uri    本地媒体 URI（content://media/...）
     * @param prompt 发给 AI 的提示词（默认为通用图片描述请求）
     * @return [AiAnalysisResult.Success] 或 [AiAnalysisResult.Error]，不会抛出异常
     */
    suspend fun analyzeImage(
        uri: Uri,
        prompt: String = "请用中文详细描述这张图片，包括场景环境、主要内容、颜色特点、情绪氛围等要素。"
    ): AiAnalysisResult
}
