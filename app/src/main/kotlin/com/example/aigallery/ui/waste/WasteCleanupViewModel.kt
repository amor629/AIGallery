package com.example.aigallery.ui.waste

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.aigallery.ai.AiState
import com.example.aigallery.ai.AiStateManager
import com.example.aigallery.domain.model.WastePhoto
import com.example.aigallery.domain.repository.IMediaRepository
import com.example.aigallery.domain.repository.IWasteRepository
import com.example.aigallery.work.WasteScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AI 废片清理 ViewModel
 *
 * 扫描本身由 [WasteScanWorker]（WorkManager 前台任务）完成，与本 ViewModel/页面生命周期解耦：
 * 点击"开始扫描"后可立即离开当前页面去看相册，扫描仍会在后台继续；每批结果实时持久化到 Room，
 * [scanState] 通过组合 WorkManager 任务状态 + 本地扫描记录 + 最新媒体库，
 * 无论何时重新进入本页面都会自动展示最新（含进行中）的结果。
 *
 * 状态机：Idle → Scanning → Done / Error
 */
@HiltViewModel
class WasteCleanupViewModel @Inject constructor(
    private val mediaRepository: IMediaRepository,
    private val wasteRepository: IWasteRepository,
    private val workManager: WorkManager,
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

    val scanState: StateFlow<ScanState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(WasteScanWorker.WORK_NAME),
        wasteRepository.getWasteResults(),
        mediaRepository.getAllMedia()
    ) { infos, wasteRecords, allMedia ->
        val mediaByUri = allMedia.associateBy { it.uri.toString() }
        val wastePhotos = wasteRecords.mapNotNull { record ->
            val reason = record.reason ?: return@mapNotNull null
            val media  = mediaByUri[record.uri] ?: return@mapNotNull null
            WastePhoto(media, reason)
        }
        when (val info = infos.firstOrNull()) {
            null -> ScanState.Idle
            else -> when (info.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> ScanState.Scanning(
                    scanned        = info.progress.getInt(WasteScanWorker.KEY_SCANNED, 0),
                    total          = info.progress.getInt(WasteScanWorker.KEY_TOTAL, 0),
                    found          = wastePhotos.size,
                    partialResults = wastePhotos
                )
                WorkInfo.State.FAILED -> ScanState.Error("扫描失败，请重试")
                WorkInfo.State.SUCCEEDED -> ScanState.Done(wastePhotos)
                else -> ScanState.Idle
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanState.Idle)

    // ---- 多选状态 ----
    private val _selectedUris = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedUris: StateFlow<Set<Uri>> = _selectedUris.asStateFlow()

    // ---- 系统删除弹窗事件 ----
    private val _deleteRequest = MutableStateFlow<IntentSender?>(null)
    val deleteRequest: StateFlow<IntentSender?> = _deleteRequest.asStateFlow()

    // ----------------------------------------------------------------
    // 扫描逻辑
    // ----------------------------------------------------------------

    /**
     * 开始扫描（幂等：只扫描此前未扫描过的照片，最多 [WasteScanWorker] 单次处理上限张）。
     * 调用后立即返回，实际扫描在后台 WorkManager 任务中进行，可放心离开当前页面。
     */
    fun startScan() {
        if (aiStateManager.state.value !is AiState.Configured) return
        _selectedUris.value = emptySet()
        val request = OneTimeWorkRequestBuilder<WasteScanWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(WasteScanWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * 清空所有扫描记录，重新扫描全部照片。
     *
     * 用途：扫描逻辑是幂等的（跳过已扫描照片），如果想让 AI 重新判断全部照片
     * （比如怀疑之前漏判），需要用户主动清空后重新扫描。
     */
    fun rescanAll() {
        if (aiStateManager.state.value !is AiState.Configured) return
        viewModelScope.launch {
            wasteRepository.clearAll()
            startScan()
        }
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

    /** 删除成功后，从扫描记录中移除已删除项目（MediaStore 中已不存在，[scanState] 也会自动过滤掉） */
    fun afterDeletion() {
        val deletedUris = _selectedUris.value
        viewModelScope.launch {
            wasteRepository.removeResults(deletedUris.map { it.toString() })
        }
        _selectedUris.value = emptySet()
        _deleteRequest.value = null
    }
}
