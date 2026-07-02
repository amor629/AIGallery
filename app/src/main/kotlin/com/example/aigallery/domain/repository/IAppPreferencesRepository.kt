package com.example.aigallery.domain.repository

import kotlinx.coroutines.flow.Flow

/** 应用级杂项偏好 Repository（自动打标开关等） */
interface IAppPreferencesRepository {
    val autoTagEnabled: Flow<Boolean>
    suspend fun setAutoTagEnabled(value: Boolean)
}
