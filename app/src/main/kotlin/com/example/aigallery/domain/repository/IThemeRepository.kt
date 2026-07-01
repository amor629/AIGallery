package com.example.aigallery.domain.repository

import com.example.aigallery.domain.model.AppTheme
import kotlinx.coroutines.flow.Flow

/**
 * 主题偏好 Repository 接口（Domain 层）
 *
 * ViewModel 只依赖此接口，不直接操作 DataStore。
 * 这样未来可以轻松替换底层存储（DataStore → Room 等）而无需修改上层。
 */
interface IThemeRepository {

    /**
     * 持续观察用户保存的主题偏好
     *
     * 冷流：有新订阅者时才读取 DataStore，值改变时自动推送新值。
     * 首次订阅若尚无存储记录，返回 [AppTheme.SYSTEM] 作为默认值。
     */
    val themeFlow: Flow<AppTheme>

    /**
     * 将用户选择的主题持久化保存到 DataStore
     *
     * @param theme 用户选择的主题枚举值
     */
    suspend fun saveTheme(theme: AppTheme)
}
