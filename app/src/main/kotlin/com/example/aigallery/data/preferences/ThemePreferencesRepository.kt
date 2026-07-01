package com.example.aigallery.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aigallery.domain.model.AppTheme
import com.example.aigallery.domain.repository.IThemeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// DataStore 文件名（单例扩展属性，整个 App 共享同一个实例）
private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "theme_preferences"
)

/**
 * 主题偏好 Repository 实现（Data 层）
 *
 * 使用 Jetpack DataStore Preferences 持久化用户选择的主题。
 * DataStore 相较于 SharedPreferences 的优势：
 *   - 完全异步（Flow + 协程），不阻塞主线程
 *   - 内置异常处理机制，读写更安全
 *   - 支持 Kotlin 类型，无需手动序列化
 */
@Singleton
class ThemePreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : IThemeRepository {

    companion object {
        // DataStore 中的键名：存储主题枚举的字符串表示
        private val KEY_THEME = stringPreferencesKey("app_theme")
    }

    /**
     * 持续观察主题偏好
     *
     * .catch：万一 DataStore 内部出错（如文件损坏），静默回退到 SYSTEM 主题
     *         而不是让 App 崩溃。
     * .map：将存储的字符串还原为 AppTheme 枚举；未存储时默认 SYSTEM。
     */
    override val themeFlow: Flow<AppTheme> = context.themeDataStore.data
        .catch { _exception ->
            // 读取异常时发出默认值，不抛出异常
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { preferences ->
            // 安全地将字符串转回枚举；若字符串不合法则回退到 SYSTEM
            val savedValue = preferences[KEY_THEME] ?: AppTheme.SYSTEM.name
            try {
                AppTheme.valueOf(savedValue)
            } catch (_: IllegalArgumentException) {
                AppTheme.SYSTEM
            }
        }

    /**
     * 将用户选择的主题写入 DataStore
     *
     * DataStore 的 edit 是挂起函数，内部使用 Dispatchers.IO，
     * 调用方（ViewModel）无需手动切线程。
     */
    override suspend fun saveTheme(theme: AppTheme) {
        context.themeDataStore.edit { preferences ->
            preferences[KEY_THEME] = theme.name
        }
    }
}
