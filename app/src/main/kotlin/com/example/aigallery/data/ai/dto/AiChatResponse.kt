package com.example.aigallery.data.ai.dto

import com.google.gson.annotations.SerializedName

/**
 * OpenAI 兼容接口 Chat Completions 响应体
 *
 * 成功时：[choices] 非空，[error] 为 null
 * 失败时：[choices] 可能为空，[error] 包含错误详情
 */
data class AiChatResponse(
    @SerializedName("choices") val choices: List<AiChoice>?,
    @SerializedName("error")   val error: AiErrorBody?
)

/** 单个回答选项 */
data class AiChoice(
    @SerializedName("message")       val message: AiResponseMessage,
    @SerializedName("finish_reason") val finishReason: String?
)

/** AI 返回的消息（content 为生成的文本） */
data class AiResponseMessage(
    @SerializedName("role")    val role: String,
    @SerializedName("content") val content: String
)

/**
 * API 错误体（HTTP 4xx / 5xx 时出现在响应 Body 中）
 *
 * ⚠️ 禁止将此对象内容直接打印到 Logcat（code 字段可能含敏感信息）
 */
data class AiErrorBody(
    @SerializedName("message") val message: String,
    @SerializedName("type")    val type: String?,
    @SerializedName("code")    val code: String?
)
