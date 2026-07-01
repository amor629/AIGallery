package com.example.aigallery.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aigallery.ai.AiState
import com.example.aigallery.ai.AiStateManager
import com.example.aigallery.domain.model.AiAnalysisResult
import com.example.aigallery.domain.repository.IAiImageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 图片详情页的 AI 识图 ViewModel
 *
 * 职责：
 * - 持有 AI 分析结果状态（Idle → Loading → Success/Error）
 * - 暴露 AI 全局配置状态（来自 [AiStateManager]）
 * - 调用 [IAiImageRepository.analyzeImage]（UI 层不直接接触 Data 层）
 * - 防止重复请求（Loading 状态时忽略新的 analyzeImage 调用）
 *
 * 生命周期：与 PhotoDetailScreen NavBackStackEntry 绑定（Hilt @HiltViewModel）
 */
@HiltViewModel
class AiDetailViewModel @Inject constructor(
    private val aiImageRepository: IAiImageRepository,
    private val aiStateManager: AiStateManager
) : ViewModel() {

    // ---- AI 分析结果（私有 MutableStateFlow，外部只读）----
    private val _analysisResult = MutableStateFlow<AiAnalysisResult>(AiAnalysisResult.Idle)

    /**
     * UI 订阅此 Flow 以渲染分析结果面板
     * Idle      → 不显示结果面板
     * Loading   → 显示"分析中…"按钮（禁用）
     * Success   → 显示描述文本卡片
     * Error     → 显示错误提示卡片（含重试按钮）
     */
    val analysisResult: StateFlow<AiAnalysisResult> = _analysisResult.asStateFlow()

    /**
     * AI 全局配置状态
     * UI 依据此状态决定点击"AI 识图"时是：
     * - NotConfigured → 弹出引导配置的对话框
     * - Configured    → 直接调用分析
     */
    val aiState: StateFlow<AiState> = aiStateManager.state

    // ----------------------------------------------------------------
    // 公开操作
    // ----------------------------------------------------------------

    /**
     * 触发 AI 图片分析
     *
     * @param uri  图片的 content:// URI（由 PhotoDetailScreen 传入）
     *
     * 注意：
     * - 若当前已在 Loading 状态，则直接忽略（防重复请求）
     * - 所有异常均由 Repository 层捕获并转换为 [AiAnalysisResult.Error]，此处不需要 try-catch
     */
    fun analyzeImage(uri: Uri) {
        // 正在分析中，忽略重复调用
        if (_analysisResult.value is AiAnalysisResult.Loading) return

        viewModelScope.launch {
            _analysisResult.value = AiAnalysisResult.Loading
            _analysisResult.value = aiImageRepository.analyzeImage(
                uri = uri,
                prompt = "请用中文详细描述这张图片的内容，包括：主体对象、场景环境、色彩构图、情感氛围等方面。如果包含文字，也请一并描述。"
            )
        }
    }

    /**
     * 清除当前分析结果，恢复 Idle 状态
     * 用于用户点击结果面板右上角"关闭"按钮时调用
     */
    fun clearResult() {
        _analysisResult.value = AiAnalysisResult.Idle
    }
}
