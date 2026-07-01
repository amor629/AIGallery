package com.example.aigallery.domain.repository

import com.example.aigallery.domain.model.AiConfig
import kotlinx.coroutines.flow.Flow

/**
 * AI 配置 Repository 接口（Domain 层抽象）
 *
 * Domain 层只依赖此接口，不依赖任何具体实现（DataStore / SharedPreferences 等）。
 * 具体实现在 data 层，通过 Hilt 注入。
 */
interface IAiConfigRepository {

    /**
     * 当前 AI 配置（响应式 Flow）
     * - 返回 null  → 用户尚未配置
     * - 返回非 null → 已有配置，AI 功能可用
     *
     * 收集此 Flow 可实时感知配置变化（保存/清除后自动更新）
     */
    val configFlow: Flow<AiConfig?>

    /**
     * 当前配置的同步快照（供拦截器等非协程环境使用）
     * 与 configFlow 的最新值保持一致
     */
    val currentConfig: AiConfig?

    /**
     * 保存用户输入的 AI 配置（加密后落盘）
     * 保存成功后，[configFlow] 会立即发出新值
     */
    suspend fun saveConfig(config: AiConfig)

    /**
     * 清除已保存的 AI 配置
     * 清除后，[configFlow] 会发出 null，AI 入口回到引导态
     */
    suspend fun clearConfig()

    /**
     * 测试与当前配置的 API 连通性
     *
     * 向 baseUrl 发送探测请求：
     * - 返回 [Result.success] → 服务器可达（HTTP 200 或 401 均视为可达）
     * - 返回 [Result.failure] → 无法连接，包含错误描述
     *
     * ⚠️ 调用前请确保已有配置（currentConfig != null）
     */
    suspend fun testConnectivity(): Result<Unit>
}
