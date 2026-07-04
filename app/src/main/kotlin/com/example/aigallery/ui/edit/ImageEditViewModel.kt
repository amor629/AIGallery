package com.example.aigallery.ui.edit

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aigallery.data.image.ImageProcessor
import com.example.aigallery.domain.repository.IMediaSaveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImageEditUiState {
    data object Idle : ImageEditUiState
    data object Loading : ImageEditUiState
    data class Ready(val bitmap: Bitmap) : ImageEditUiState
    data class Saved(val uri: Uri, val message: String) : ImageEditUiState
    data class Error(val message: String) : ImageEditUiState
}

@HiltViewModel
class ImageEditViewModel @Inject constructor(
    private val mediaSaveRepository: IMediaSaveRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImageEditUiState>(ImageEditUiState.Idle)
    val uiState: StateFlow<ImageEditUiState> = _uiState.asStateFlow()

    private var sourceBitmap: Bitmap? = null

    fun loadImage(uri: Uri) {
        if (_uiState.value is ImageEditUiState.Ready) return
        viewModelScope.launch {
            _uiState.value = ImageEditUiState.Loading
            val bitmap = mediaSaveRepository.loadBitmap(uri)
            if (bitmap == null) {
                _uiState.value = ImageEditUiState.Error("无法加载图片")
            } else {
                sourceBitmap = bitmap
                _uiState.value = ImageEditUiState.Ready(bitmap)
            }
        }
    }

    fun saveMosaic(mask: Bitmap) = saveEdited { base ->
        ImageProcessor.mosaic(base, mask)
    }

    fun saveCrop(left: Int, top: Int, width: Int, height: Int) = saveEdited { base ->
        ImageProcessor.crop(base, left, top, width, height)
    }

    fun saveDoodle(doodleLayer: Bitmap) = saveEdited { base ->
        ImageProcessor.mergeDoodle(base, doodleLayer)
    }

    private fun saveEdited(processor: suspend (Bitmap) -> Bitmap?) {
        val base = sourceBitmap ?: return
        viewModelScope.launch {
            _uiState.value = ImageEditUiState.Loading
            val result = processor(base)
            if (result == null) {
                _uiState.value = ImageEditUiState.Error("处理失败")
                return@launch
            }
            val name = "编辑_${System.currentTimeMillis()}.jpg"
            val uri = mediaSaveRepository.saveBitmap(result, name)
            if (uri == null) {
                _uiState.value = ImageEditUiState.Error("保存失败")
            } else {
                if (result !== base) result.recycle()
                _uiState.value = ImageEditUiState.Saved(uri, "已保存到相册")
            }
        }
    }

    override fun onCleared() {
        sourceBitmap?.recycle()
        super.onCleared()
    }
}
