package com.example.aigallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aigallery.domain.model.AppTheme
import com.example.aigallery.domain.repository.IThemeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 应用级 ViewModel（由 MainActivity 持有）
 *
 * 职责：
 * - 订阅 [IThemeRepository.themeFlow]，将主题偏好暴露为 [StateFlow]
 * - MainActivity 通过 `by viewModels<MainViewModel>()` 获取实例
 *
 * 为什么不在 Activity 里直接字段注入 Repository？
 * 使用 ViewModel 可以避免 Activity 与 Data 层的直接耦合，
 * 同时 Hilt 的 @HiltViewModel 走 KSP 路径，更稳定。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    themeRepository: IThemeRepository
) : ViewModel() {

    /**
     * 当前应用主题（StateFlow，首次值为 SYSTEM）
     *
     * WhileSubscribed(5_000)：UI 从屏幕退出后 5 秒内仍保持订阅，
     * 避免屏幕旋转时重新加载 DataStore。
     */
    val appTheme: StateFlow<AppTheme> = themeRepository.themeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppTheme.SYSTEM
        )
}
