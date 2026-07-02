package com.example.aigallery.ui.waste

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aigallery.ai.AiState
import com.example.aigallery.ai.AiStateManager
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.domain.model.WastePhoto
import com.example.aigallery.domain.repository.IAiSearchRepository
import com.example.aigallery.domain.repository.IMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AI 废片清理 ViewModel
 *
 * 状态机：Idle → Scanning → Done / Error
 * - Idle：等待用户点击"开始扫描"
 * - Scanning：分批调用 [IAiSearchRepository.analyzeWasteBatch]，实时更新进度
 * - Done：扫描完毕，持有废片列表；支持选中/删除
 * - Error：AI 未配置或网络异常
 */
@HiltViewModel
class WasteCleanupViewModel @Inject constructor(
    private val mediaRepository: IMediaRepository,
    private val aiSearchRepository: IAiSearchRepository,
    private val aiStateManager: AiStateManager
) : ViewModel() {

    // ---- 扫描状态 ----
    sealed interface ScanState {
        /** 等待用户触发扫描 */
        data object Idle : ScanState

        /** 扫描进行中（实时更新） */
        data class Scanning(
            val scanned: Int,
            val total: Int,
            val found: Int,
            val partialResults: List<WastePhoto>
        ) : ScanState

        /** 扫描完成 */
        data class Done(val results: List<WastePhoto>) : ScanState

        /** 错误（AI 未配置 / 网络失败） */
        data class Error(val message: String) : ScanState
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // ---- 多选状态 ----
    private val _selectedUris = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedUris: StateFlow<Set<Uri>> = _selectedUris.asStateFlow()

    // ---- 系统删除弹窗事件 ----
    private val _deleteRequest = MutableStateFlow<IntentSender?>(null)
    val deleteRequest: StateFlow<IntentSender?> = _deleteRequest.asStateFlow()

    // ----------------------------------------------------------------
    // 扫描逻辑
    // ----------------------------------------------------------------

    /** 开始扫描（最多扫描 [MAX_SCAN] 张图片） */
    fun startScan() {
        if (aiStateManager.state.value !is AiState.Configured) {
            _scanState.value = ScanState.Error("AI 未配置，请先在设置页填写 API 地址和 Key")
            return
        }
        viewModelScope.launch {
            _scanState.value = ScanState.Scanning(0, 0, 0, emptyList())
            _selectedUris.value = emptySet()
            try {
                val allImages = mediaRepository.getAllMedia().first()
                    .filter { it.mediaType == MediaType.IMAGE }
                    .take(MAX_SCAN)

                if (allImages.isEmpty()) {
                    _scanState.value = ScanState.Done(emptyList())
                    return@launch
                }

                val results = mutableListOf<WastePhoto>()

                // ① 截图：直接用文件夹元数据识别，不需要调用 AI
                val screenshots = allImages.filter { it.isScreenshot }
                    .map { WastePhoto(it, "截图") }
                results.addAll(screenshots)

                // ② 非截图照片：用 AI 识别模糊 / 闭眼 / 重复
                //    重要：按拍摄时间排序后将"时间相近"（3秒内）的照片放在同一批次，
                //    这样连拍/重复拍的照片会在同一批次内被模型比对，大幅提升重复检测率
                val nonScreenshots = allImages
                    .filter { !it.isScreenshot }
                    .sortedByDescending { if (it.dateTaken > 0) it.dateTaken else it.dateAdded }

                val batches = buildTemporalBatches(nonScreenshots)
                var scanned = 0

                for (batch in batches) {
                    val batchResults = aiSearchRepository.analyzeWasteBatch(batch)
                    results.addAll(batchResults)
                    scanned += batch.size
                    _scanState.value = ScanState.Scanning(
                        scanned  = scanned + screenshots.size,
                        total    = allImages.size,
                        found    = results.size,
                        partialResults = results.toList()
                    )
                }
                _scanState.value = ScanState.Done(results.toList())
            } catch (e: Exception) {
                _scanState.value = ScanState.Error(e.message ?: "扫描失败，请重试")
            }
        }
    }

    /**
     * 将照片列表按时间临近度分批：
     * - 拍摄时间相差 ≤ [DUPLICATE_WINDOW_MS] 的照片放入同一批次（提升重复检测率）
     * - 每批最多 [BATCH_SIZE] 张，超出则新建批次
     */
    private fun buildTemporalBatches(items: List<MediaItem>): List<List<MediaItem>> {
        if (items.isEmpty()) return emptyList()
        val batches = mutableListOf<MutableList<MediaItem>>()
        var currentBatch = mutableListOf<MediaItem>()
        var prevTime = Long.MIN_VALUE

        for (item in items) {
            val t = if (item.dateTaken > 0) item.dateTaken else item.dateAdded
            val isClose = prevTime != Long.MIN_VALUE && (prevTime - t) <= DUPLICATE_WINDOW_MS
            if (currentBatch.isEmpty() || (isClose && currentBatch.size < BATCH_SIZE)) {
                currentBatch.add(item)
            } else {
                batches.add(currentBatch)
                currentBatch = mutableListOf(item)
            }
            prevTime = t
        }
        if (currentBatch.isNotEmpty()) batches.add(currentBatch)
        return batches
    }

    // ----------------------------------------------------------------
    // 选中逻辑
    // ----------------------------------------------------------------

    fun toggleSelection(uri: Uri) {
        _selectedUris.update { if (uri in it) it - uri else it + uri }
    }

    fun toggleSelectAll(allUris: List<Uri>) {
        _selectedUris.update { current ->
            if (current.size < allUris.size) allUris.toSet() else emptySet()
        }
    }

    // ----------------------------------------------------------------
    // 删除逻辑
    // ----------------------------------------------------------------

    /** 请求删除选中的废片（发起系统确认弹窗） */
    fun requestDelete() {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _deleteRequest.value = mediaRepository.buildDeleteRequest(uris)
        }
    }

    /** 系统弹窗已启动后清除请求（防止旋转重复弹出） */
    fun clearDeleteRequest() {
        _deleteRequest.value = null
    }

    /** 删除成功后，从结果列表移除已删除项目 */
    fun afterDeletion() {
        val deletedUris = _selectedUris.value
        val current = _scanState.value as? ScanState.Done ?: run {
            _deleteRequest.value = null
            return
        }
        val remaining = current.results.filter { it.mediaItem.uri !in deletedUris }
        _scanState.value = ScanState.Done(remaining)
        _selectedUris.value = emptySet()
        _deleteRequest.value = null
    }

    companion object {
        /** 单次扫描最多处理图片数，平衡 API 消耗与覆盖率 */
        private const val MAX_SCAN = 90
        /** 每批发送给视觉 AI 的图片数（不超过 3，控制 Token）*/
        private const val BATCH_SIZE = 3
        /** 判断为"时间相近"（可能是连拍重复）的时间窗口：3 秒 */
        private const val DUPLICATE_WINDOW_MS = 3_000L
    }
}
