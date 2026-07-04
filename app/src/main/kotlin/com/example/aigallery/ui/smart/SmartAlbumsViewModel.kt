package com.example.aigallery.ui.smart

import android.content.Context
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
import com.example.aigallery.data.local.db.TagAlbumRow
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.repository.IMediaRepository
import com.example.aigallery.domain.repository.ITagRepository
import com.example.aigallery.work.PhotoTagWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SmartAlbumsScanState {
    data object Idle     : SmartAlbumsScanState
    data object Scanning : SmartAlbumsScanState
    data object Done     : SmartAlbumsScanState
    data class  Error(val message: String) : SmartAlbumsScanState
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TagRepoEntryPoint {
    fun tagRepository(): ITagRepository
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SmartAlbumsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val mediaRepository: IMediaRepository,
    private val workManager: WorkManager,
    private val aiStateManager: AiStateManager
) : ViewModel() {

    private val tagRepository: ITagRepository by lazy {
        EntryPointAccessors.fromApplication(appContext, TagRepoEntryPoint::class.java)
            .tagRepository()
    }

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    val tagAlbums: StateFlow<List<TagAlbumRow>> = tagRepository.getTagAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scanState: StateFlow<SmartAlbumsScanState> =
        workManager.getWorkInfosForUniqueWorkFlow(PhotoTagWorker.WORK_NAME)
            .map { infos ->
                when {
                    infos.any { it.state == WorkInfo.State.RUNNING   } -> SmartAlbumsScanState.Scanning
                    infos.any { it.state == WorkInfo.State.FAILED    } -> SmartAlbumsScanState.Error("扫描失败，请重试")
                    infos.any { it.state == WorkInfo.State.SUCCEEDED } -> SmartAlbumsScanState.Done
                    else -> SmartAlbumsScanState.Idle
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartAlbumsScanState.Idle)

    val photosForTag: StateFlow<List<MediaItem>> = _selectedTag
        .flatMapLatest { tag ->
            if (tag == null) return@flatMapLatest flowOf(emptyList())
            combine(tagRepository.getPhotoUrisByTag(tag), mediaRepository.getAllMedia()) { uris, items ->
                val uriSet = uris.toSet()
                items.filter { it.uri.toString() in uriSet }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startScan() {
        if (aiStateManager.state.value !is AiState.Configured) return
        val request = OneTimeWorkRequestBuilder<PhotoTagWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(PhotoTagWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 清空所有已打标签，重新扫描全部照片。
     *
     * 用途：打标逻辑是幂等的（跳过已打标照片），升级标签规则/类别后，
     * 已经打过标的旧照片不会自动用新规则重新生成，需要用户主动清空后重新扫描才能生效。
     */
    fun resetAndRescan() {
        if (aiStateManager.state.value !is AiState.Configured) return
        viewModelScope.launch {
            tagRepository.clearAll()
            startScan()
        }
    }

    fun selectTag(tag: String) { _selectedTag.value = tag }
    fun clearTag()              { _selectedTag.value = null }
}
