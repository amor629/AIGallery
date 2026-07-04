package com.example.aigallery.ui.edit

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aigallery.domain.repository.IMediaSaveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface VideoTrimUiState {
    data object Idle : VideoTrimUiState
    data object Exporting : VideoTrimUiState
    data class Saved(val uri: Uri) : VideoTrimUiState
    data class Error(val message: String) : VideoTrimUiState
}

@HiltViewModel
class VideoTrimViewModel @Inject constructor(
    private val mediaSaveRepository: IMediaSaveRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<VideoTrimUiState>(VideoTrimUiState.Idle)
    val uiState: StateFlow<VideoTrimUiState> = _uiState.asStateFlow()

    fun exportTrim(sourceUri: Uri, startMs: Long, endMs: Long, displayName: String) {
        if (_uiState.value is VideoTrimUiState.Exporting) return
        viewModelScope.launch {
            _uiState.value = VideoTrimUiState.Exporting
            val uri = mediaSaveRepository.saveVideoTrim(
                sourceUri = sourceUri,
                startMs = startMs,
                endMs = endMs,
                displayName = displayName
            )
            _uiState.value = if (uri != null) {
                VideoTrimUiState.Saved(uri)
            } else {
                VideoTrimUiState.Error("视频导出失败，请重试")
            }
        }
    }

    fun resetState() {
        _uiState.value = VideoTrimUiState.Idle
    }
}
