package com.example.aigallery.domain.model

/**
 * AI 配置数据模型（Domain 层）
 *
 * 存储用户在设置页自填的 API 连接信息。
 * ⚠️ 此模型只在内存中流转，持久化层负责加密落盘，禁止以明文写入日志。
 *
 * @param baseUrl  用户填写的 API Base URL，例如 "https://api.openai.com/v1"
 * @param apiKey   用户填写的 API Key（敏感字段，不可打印到日志）
 */
data class AiConfig(
    val baseUrl: String,
    val apiKey: String
) {
    /**
     * 用于日志的安全字符串：隐藏 apiKey，只显示 baseUrl
     * 防止 Key 泄漏到 Logcat / Crash 报告
     */
    override fun toString(): String =
        "AiConfig(baseUrl='$baseUrl', apiKey='***REDACTED***')"
}
