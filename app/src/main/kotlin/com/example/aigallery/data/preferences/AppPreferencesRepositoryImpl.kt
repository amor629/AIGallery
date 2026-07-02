package com.example.aigallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.example.aigallery.domain.repository.IAppPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appPrefsDataStore by preferencesDataStore(name = "app_prefs")

/** 应用级杂项偏好持久化（DataStore） */
@Singleton
class AppPreferencesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : IAppPreferencesRepository {

    private val AUTO_TAG_KEY = booleanPreferencesKey("auto_tag_enabled")

    override val autoTagEnabled = context.appPrefsDataStore.data
        .map { it[AUTO_TAG_KEY] ?: false }

    override suspend fun setAutoTagEnabled(value: Boolean) {
        context.appPrefsDataStore.edit { it[AUTO_TAG_KEY] = value }
    }
}
