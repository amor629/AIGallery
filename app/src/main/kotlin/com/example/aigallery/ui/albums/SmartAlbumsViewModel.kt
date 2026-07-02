package com.example.aigallery.ui.albums

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aigallery.data.local.db.TagAlbumRow
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.repository.IMediaRepository
import com.example.aigallery.domain.repository.ITagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 智能相册 ViewModel
 *
 * 两级导航：
 * - [selectedTag] == null → 标签列表（所有分类）
 * - [selectedTag] != null → 单个分类的照片网格
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SmartAlbumsViewModel @Inject constructor(
    private val tagRepository: ITagRepository,
    private val mediaRepository: IMediaRepository
) : ViewModel() {

    /** 当前选中的标签（null = 显示标签列表首页） */
    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    /** 所有标签相册（标签名 + 数量 + 封面 URI） */
    val tagAlbums: StateFlow<List<TagAlbumRow>> = tagRepository.getTagAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前标签下的照片列表（selectedTag 变化时自动切换） */
    val photosForTag: StateFlow<List<MediaItem>> = _selectedTag
        .flatMapLatest { tag ->
            if (tag == null) return@flatMapLatest flowOf(emptyList())
            // 将 URI 字符串映射回完整 MediaItem
            combine(tagRepository.getPhotoUrisByTag(tag), mediaRepository.getAllMedia()) { uris, items ->
                val uriSet = uris.toSet()
                items.filter { it.uri.toString() in uriSet }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectTag(tag: String) { _selectedTag.value = tag }
    fun clearTag()              { _selectedTag.value = null }
}
