package com.example.aigallery.ai

import com.example.aigallery.di.ApplicationScope
import com.example.aigallery.domain.model.AiConfig
import com.example.aigallery.domain.repository.IAiConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// AI 全局状态定义
// ============================================================

/**
 * AI 功能的三种状态（密封接口，穷举处理，不遗漏任何状态）
 */
sealed interface AiState {

    /**
     * 未配置：用户尚未填写 API Key / Base URL
     * UI 行为：AI 入口显示"配置 AI 功能"引导 UI，不报错不崩溃
     */
    data object NotConfigured : AiState

    /**
     * 已配置：用户已填写配置，AI 功能可以尝试调用
     * UI 行为：AI 入口正常显示，功能可用
     */
    data object Configured : AiState

    /**
     * 暂不可用：已配置但 API 调用失败（网络故障 / Key 失效等）
     * UI 行为：AI 功能静默降级，显示错误提示，基础相册功能不受影响
     *
     * @param reason 简短的人类可读错误描述（禁止包含 API Key 内容）
     */
    data class Unavailable(val reason: String) : AiState
}

// ============================================================
// AI 状态管理器
// ============================================================

/**
 * 全局 AI 状态管理器（单例）
 *
 * 职责：
 * 1. 收集 [IAiConfigRepository.configFlow]，将配置状态映射为 [AiState]
 * 2. 通过 [state] StateFlow 向整个应用广播当前 AI 状态
 * 3. 作为 ViewModel 判断"是否显示 AI 功能入口"的唯一真相来源
 *
 * 核心原则：
 * - 未配置时，[state] 为 [AiState.NotConfigured]，UI 显示引导态
 * - AI 状态变化只能由用户操作（保存/清除配置）触发，不会自动变化
 */
@Singleton
class AiStateManager @Inject constructor(
    repository: IAiConfigRepository,
    @ApplicationScope scope: CoroutineScope
) {
    /**
     * 当前 AI 状态（StateFlow，可在 Compose 中直接 collectAsStateWithLifecycle）
     *
     * 初始值为 [AiState.NotConfigured]，等待 Repository 初始化完成后更新。
     * SharingStarted.Eagerly 确保应用启动时立即开始收集，无需等待 UI 订阅。
     */
    val state: StateFlow<AiState> = repository.configFlow
        .map { config: AiConfig? ->
            if (config == null) AiState.NotConfigured
            else AiState.Configured
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AiState.NotConfigured
        )

    /**
     * 快速检查：AI 是否已配置且可用
     * 供非响应式场景（如单次判断）使用
     */
    val isAvailable: Boolean
        get() = state.value is AiState.Configured
}
