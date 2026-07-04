package com.example.aigallery.ui.detail

import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aigallery.ai.AiState
import com.example.aigallery.ai.AiStateManager
import com.example.aigallery.domain.model.AiAnalysisResult
import com.example.aigallery.domain.model.AiEditType
import com.example.aigallery.domain.model.AiImageEditResult
import com.example.aigallery.domain.repository.IAiImageEditRepository
import com.example.aigallery.domain.repository.IAiImageRepository
import com.example.aigallery.domain.repository.IMediaRepository
import com.example.aigallery.domain.repository.IMediaSaveRepository
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
    private val aiImageEditRepository: IAiImageEditRepository,
    private val mediaSaveRepository: IMediaSaveRepository,
    private val mediaRepository: IMediaRepository,
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

    // ---- AI 朋友圈文案生成结果（与识图结果相互独立，弹窗展示）----
    private val _captionResult = MutableStateFlow<AiAnalysisResult>(AiAnalysisResult.Idle)

    /**
     * UI 订阅此 Flow 以展示"AI 写文案"弹窗
     * Idle → 不显示弹窗；Loading → 按钮显示加载动画；Success/Error → 弹出结果对话框
     */
    val captionResult: StateFlow<AiAnalysisResult> = _captionResult.asStateFlow()

    // ---- AI 图片编辑（老照片修复/AI美化，无需涂抹蒙版）结果 ----
    private val _imageEditResult = MutableStateFlow<AiImageEditResult>(AiImageEditResult.Idle)
    val imageEditResult: StateFlow<AiImageEditResult> = _imageEditResult.asStateFlow()

    /** 一次性事件：用户选择"覆盖原图"时，发起系统写入授权弹窗 */
    private val _writeRequest = MutableStateFlow<IntentSender?>(null)
    val writeRequest: StateFlow<IntentSender?> = _writeRequest.asStateFlow()

    /** 等待写入授权结果期间暂存的待覆盖数据 */
    private var pendingOverwrite: Pair<Uri, Bitmap>? = null

    /** 一次性事件：详情页点击"删除"确认后，发起系统删除授权弹窗 */
    private val _deleteRequest = MutableStateFlow<IntentSender?>(null)
    val deleteRequest: StateFlow<IntentSender?> = _deleteRequest.asStateFlow()

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

    /**
     * 触发 AI 朋友圈文案生成
     *
     * 复用 [IAiImageRepository.analyzeImage]，仅通过定制 prompt 让 AI
     * 输出一段适合发朋友圈的短文案，而非详细的场景描述。
     *
     * @param uri 图片的 content:// URI（由 PhotoDetailScreen 传入）
     */
    fun generateCaption(uri: Uri) {
        // 正在生成中，忽略重复调用
        if (_captionResult.value is AiAnalysisResult.Loading) return

        viewModelScope.launch {
            _captionResult.value = AiAnalysisResult.Loading
            _captionResult.value = aiImageRepository.analyzeImage(
                uri = uri,
                prompt = "请根据这张图片生成一段适合发朋友圈的文案。" +
                    "要求：50字以内，语气自然生动、贴近生活，包含2-4个恰当的emoji表情；" +
                    "只输出文案正文本身，不要加引号、不要输出任何解释或前后缀说明。"
            )
        }
    }

    /**
     * 清除当前文案生成结果，恢复 Idle 状态（关闭弹窗时调用）
     */
    fun clearCaption() {
        _captionResult.value = AiAnalysisResult.Idle
    }

    /**
     * AI 图片编辑（老照片修复 / AI 照片美化，无需用户涂抹蒙版）
     * 处理完成后进入 [AiImageEditResult.ReadyToSave]，等待用户选择保存方式
     */
    fun processImageEdit(uri: Uri, type: AiEditType) {
        if (_imageEditResult.value is AiImageEditResult.Loading) return
        viewModelScope.launch {
            _imageEditResult.value = AiImageEditResult.Loading
            _imageEditResult.value = aiImageEditRepository.processImage(uri, type)
        }
    }

    /** 另存为新图片 */
    fun saveAsNew(bitmap: Bitmap, type: AiEditType) {
        viewModelScope.launch {
            val name = "AI_${type.name.lowercase()}_${System.currentTimeMillis()}.jpg"
            val saved = mediaSaveRepository.saveBitmap(bitmap, name, "image/jpeg")
            _imageEditResult.value = if (saved != null) {
                AiImageEditResult.Saved("已另存为新图片")
            } else {
                AiImageEditResult.Error("保存失败，请检查存储权限")
            }
        }
    }

    /** 覆盖原图：先申请系统写入授权，用户确认后由 [onWriteRequestResult] 真正写入 */
    fun requestOverwrite(uri: Uri, bitmap: Bitmap) {
        pendingOverwrite = uri to bitmap
        viewModelScope.launch {
            _writeRequest.value = mediaSaveRepository.buildWriteRequest(uri)
        }
    }

    /** UI 收到系统授权弹窗结果后回调 */
    fun onWriteRequestResult(granted: Boolean) {
        _writeRequest.value = null
        val (uri, bitmap) = pendingOverwrite ?: return
        pendingOverwrite = null
        if (!granted) return // 用户取消，保留 ReadyToSave 状态供重新选择
        viewModelScope.launch {
            val ok = mediaSaveRepository.overwriteBitmap(uri, bitmap)
            _imageEditResult.value = if (ok) {
                AiImageEditResult.Saved("已覆盖原图")
            } else {
                AiImageEditResult.Error("覆盖失败，请重试")
            }
        }
    }

    fun clearImageEditResult() {
        _imageEditResult.value = AiImageEditResult.Idle
    }

    /** 翻页时重置所有 AI 状态 */
    fun clearAllOnPageChange() {
        clearResult()
        clearCaption()
        clearImageEditResult()
    }

    // ----------------------------------------------------------------
    // 详情页单张删除（等同于相册长按多选删除，仅针对当前查看的这一张）
    // ----------------------------------------------------------------

    /**
     * 用户在详情页点击"删除"并二次确认后调用：
     * 构建系统删除授权弹窗（Android 10+ 强制要求），UI 收到 IntentSender 后启动系统弹窗。
     */
    fun requestDelete(uri: Uri) {
        viewModelScope.launch {
            _deleteRequest.value = mediaRepository.buildDeleteRequest(listOf(uri))
        }
    }

    /** 系统删除弹窗已启动后清除请求（防止屏幕旋转重复触发） */
    fun clearDeleteRequest() {
        _deleteRequest.value = null
    }
}
