package com.example.aigallery.data.ai

import com.example.aigallery.data.ai.dto.AiChatRequest
import com.example.aigallery.data.ai.dto.AiChatResponse
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Retrofit 接口：AI Chat Completions API
 *
 * 核心设计：使用 @Url 注解支持动态 Base URL。
 * 每次请求前从 [AiApiClient.requireBaseUrl] 获取完整端点地址，
 * 避免 Retrofit 实例初始化时绑定固定 URL（用户配置的地址可随时更改）。
 *
 * 兼容性：
 * - 阿里云百炼 DashScope: https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 * - 任何 OpenAI 兼容接口（如 Azure OpenAI、本地 Ollama 等）
 */
interface AiChatService {

    /**
     * 发送多模态对话请求（文本 + 图片）
     *
     * @param url     完整的端点 URL（含路径），覆盖 Retrofit 初始化时的 baseUrl
     * @param request 请求体（含模型名、图片 + 文本消息）
     * @return        响应体（Gson 自动反序列化）
     */
    @POST
    suspend fun chatCompletion(
        @Url url: String,
        @Body request: AiChatRequest
    ): AiChatResponse
}
