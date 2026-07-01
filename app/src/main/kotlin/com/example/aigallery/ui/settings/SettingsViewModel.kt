package com.example.aigallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aigallery.ai.AiState
import com.example.aigallery.ai.AiStateManager
import com.example.aigallery.domain.model.AiConfig
import com.example.aigallery.domain.model.AppTheme
import com.example.aigallery.domain.repository.IAiConfigRepository
import com.example.aigallery.domain.repository.IThemeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ============================================================
// UI 状态模型
// ============================================================

/**
 * 设置页 UI 状态（单一数据流，ViewModel 通过 StateFlow 暴露）
 *
 * @param aiState        当前 AI 全局状态（驱动状态指示器的显示）
 * @param currentTheme   当前应用主题（驱动主题切换开关的选中状态）
 * @param baseUrlInput   用户正在输入的 Base URL
 * @param apiKeyInput    用户正在输入的 API Key
 * @param isSaving       是否正在保存（显示加载指示器）
 * @param isTesting      是否正在测试连接（显示加载指示器）
 * @param snackbarMessage 操作结果消息（null 表示无消息）
 */
data class SettingsUiState(
    val aiState: AiState = AiState.NotConfigured,
    val currentTheme: AppTheme = AppTheme.SYSTEM,
    val baseUrlInput: String = "",
    val apiKeyInput: String = "",
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val snackbarMessage: String? = null
)

// ============================================================
// ViewModel
// ============================================================

/**
 * 设置页 ViewModel
 *
 * 职责：
 * - 持有用户输入状态（baseUrlInput、apiKeyInput）
 * - 调用 [IAiConfigRepository] 保存 / 清除配置
 * - 调用 [IAiConfigRepository.testConnectivity] 测试连接
 * - 将 [AiStateManager.state] 合并进 UI 状态
 *
 * UI 层（SettingsScreen）只调用此 ViewModel 的方法，不直接碰 Repository。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: IAiConfigRepository,
    private val aiStateManager: AiStateManager,
    private val themeRepository: IThemeRepository
) : ViewModel() {

    // ---- 内部可变状态（仅 ViewModel 内部修改）----
    private val _inputState = MutableStateFlow(
        SettingsUiState(
            // 恢复上次保存的 baseUrl（apiKey 不预填，要求用户重新确认）
            baseUrlInput = repository.currentConfig?.baseUrl ?: ""
        )
    )

    // ---- 对外暴露的 UI 状态：合并输入状态 + AI 全局状态 + 主题 ----
    val uiState: StateFlow<SettingsUiState> = combine(
        _inputState,
        aiStateManager.state,
        themeRepository.themeFlow
    ) { inputState, aiState, theme ->
        inputState.copy(aiState = aiState, currentTheme = theme)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = _inputState.value
    )

    // ----------------------------------------------------------------
    // 用户输入处理
    // ----------------------------------------------------------------

    /** 用户修改 Base URL 输入框 */
    fun onBaseUrlChange(value: String) {
        _inputState.update { it.copy(baseUrlInput = value) }
    }

    /** 用户修改 API Key 输入框 */
    fun onApiKeyChange(value: String) {
        _inputState.update { it.copy(apiKeyInput = value) }
    }

    // ----------------------------------------------------------------
    // 保存配置
    // ----------------------------------------------------------------

    /**
     * 保存用户输入的 AI 配置
     * 成功后 AiStateManager 自动切换到 Configured 状态
     */
    fun saveConfig() {
        val baseUrl = _inputState.value.baseUrlInput.trim()
        val apiKey  = _inputState.value.apiKeyInput.trim()

        // 输入校验
        if (baseUrl.isBlank()) {
            _inputState.update { it.copy(snackbarMessage = "请输入 API 地址") }
            return
        }
        if (apiKey.isBlank()) {
            _inputState.update { it.copy(snackbarMessage = "请输入 API Key") }
            return
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            _inputState.update { it.copy(snackbarMessage = "API 地址必须以 http:// 或 https:// 开头") }
            return
        }

        viewModelScope.launch {
            _inputState.update { it.copy(isSaving = true) }
            try {
                repository.saveConfig(AiConfig(baseUrl = baseUrl, apiKey = apiKey))
                // 保存成功后清空 apiKey 输入框（安全起见不长期驻留内存）
                _inputState.update {
                    it.copy(
                        isSaving = false,
                        apiKeyInput = "",
                        snackbarMessage = "AI 配置已保存"
                    )
                }
            } catch (e: Exception) {
                _inputState.update {
                    it.copy(isSaving = false, snackbarMessage = "保存失败：${e.message}")
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // 清除配置
    // ----------------------------------------------------------------

    /**
     * 清除已保存的 AI 配置
     * 成功后 AiStateManager 自动切换到 NotConfigured 状态
     */
    fun clearConfig() {
        viewModelScope.launch {
            repository.clearConfig()
            _inputState.update {
                it.copy(
                    baseUrlInput = "",
                    apiKeyInput = "",
                    snackbarMessage = "AI 配置已清除"
                )
            }
        }
    }

    // ----------------------------------------------------------------
    // 测试连通性
    // ----------------------------------------------------------------

    /**
     * 向已配置的 API 地址发送探测请求
     * 通过 snackbarMessage 告知用户测试结果
     */
    fun testConnectivity() {
        viewModelScope.launch {
            _inputState.update { it.copy(isTesting = true, snackbarMessage = null) }

            val result = repository.testConnectivity()

            _inputState.update {
                it.copy(
                    isTesting = false,
                    snackbarMessage = if (result.isSuccess) "连接成功 ✓ 服务器可达"
                                      else "连接失败：${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    // ----------------------------------------------------------------
    // 消费 Snackbar 消息（显示后调用，防止重复显示）
    // ----------------------------------------------------------------
    fun onSnackbarShown() {
        _inputState.update { it.copy(snackbarMessage = null) }
    }

    // ----------------------------------------------------------------
    // 切换主题
    // ----------------------------------------------------------------

    /**
     * 将用户选择的主题持久化到 DataStore
     *
     * 调用方：设置页的主题选择器（因此在 SettingsViewModel 里）
     * 保存完成后 themeRepository.themeFlow 会自动推送新少、
     * MainActivity 订阅的 Flow 也会收到新少，UI 自动重组。
     */
    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            themeRepository.saveTheme(theme)
        }
    }
}
