package com.example.aigallery.data.ai.dto

import com.google.gson.annotations.SerializedName

/**
 * OpenAI 兼容接口 Chat Completions 请求体
 *
 * 兼容 DashScope 百炼平台 qwen-vl-max 多模态模型。
 * 所有字段名通过 @SerializedName 严格对应 API 规范，确保 Gson 序列化正确。
 *
 * 示例请求结构：
 * ```json
 * {
 *   "model": "qwen-vl-max",
 *   "messages": [{
 *     "role": "user",
 *     "content": [
 *       {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."} },
 *       {"type": "text", "text": "请描述这张图片"}
 *     ]
 *   }],
 *   "max_tokens": 1024
 * }
 * ```
 */
data class AiChatRequest(
    @SerializedName("model")      val model: String,
    @SerializedName("messages")   val messages: List<AiMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 1024
)

/** 对话消息（role = "user" / "assistant" / "system"） */
data class AiMessage(
    @SerializedName("role")    val role: String,
    @SerializedName("content") val content: List<AiContentPart>
)

/**
 * 消息内容片段（多模态支持：文本 + 图片 URL）
 *
 * 文本片段：type="text"，仅填 [text]
 * 图片片段：type="image_url"，仅填 [imageUrl]
 */
data class AiContentPart(
    @SerializedName("type")      val type: String,
    @SerializedName("text")      val text: String? = null,
    @SerializedName("image_url") val imageUrl: AiImageUrl? = null
)

/** 图片 URL 包装（支持 Base64 data: URI 或公网 https:// URL） */
data class AiImageUrl(
    @SerializedName("url") val url: String
)
