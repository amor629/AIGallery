package com.example.aigallery.ui.hidden

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.repository.IHiddenPhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 隐藏相册 ViewModel
 *
 * 职责：
 * 1. 展示已隐藏的照片/视频列表（响应式，恢复后自动刷新）
 * 2. 支持多选 + 批量"恢复"（移回系统相册）
 *
 * 与 [com.example.aigallery.ui.gallery.GalleryViewModel] 的分工：
 * "隐藏"这一动作在主相册的多选模式里发起（需要系统删除确认弹窗，与批量删除是同一套机制）；
 * 本 ViewModel 只负责"隐藏之后"的浏览与恢复，不重复实现隐藏逻辑。
 */
@HiltViewModel
class HiddenAlbumViewModel @Inject constructor(
    private val hiddenPhotoRepository: IHiddenPhotoRepository
) : ViewModel() {

    val hiddenPhotos: StateFlow<List<MediaItem>> = hiddenPhotoRepository.getHiddenPhotos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- 多选状态 ----
    private val _isSelecting = MutableStateFlow(false)
    val isSelecting: StateFlow<Boolean> = _isSelecting.asStateFlow()

    private val _selectedUris = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedUris: StateFlow<Set<Uri>> = _selectedUris.asStateFlow()

    // ---- 恢复结果提示（一次性消息，展示后调用 [clearToastMessage] 置空）----
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    fun enterSelectionMode(uri: Uri) {
        _isSelecting.value = true
        _selectedUris.update { it + uri }
    }

    fun toggleSelection(uri: Uri) {
        _selectedUris.update { current -> if (uri in current) current - uri else current + uri }
    }

    fun clearSelection() {
        _isSelecting.value = false
        _selectedUris.value = emptySet()
    }

    fun toggleSelectAll() {
        val allUris = hiddenPhotos.value.map { it.uri }.toSet()
        _selectedUris.update { current -> if (current.size < allUris.size) allUris else emptySet() }
    }

    /** 恢复选中的照片：移回系统相册，并从隐藏索引中移除 */
    fun restoreSelected() {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val success = hiddenPhotoRepository.restore(uris)
            _toastMessage.value = if (success > 0) "已恢复 $success 张到系统相册" else "恢复失败，请重试"
            clearSelection()
        }
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }
}
