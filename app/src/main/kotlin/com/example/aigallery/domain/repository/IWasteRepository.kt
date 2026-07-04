package com.example.aigallery.domain.repository

import com.example.aigallery.data.local.db.WastePhotoEntity
import kotlinx.coroutines.flow.Flow

/** 废片扫描记录 Repository 接口（Domain 层抽象） */
interface IWasteRepository {
    /** 响应式返回所有已判定为废片的记录 */
    fun getWasteResults(): Flow<List<WastePhotoEntity>>
    /** 所有已扫描过的照片 URI（无论是否判定为废片），Worker 跳过已处理图片用 */
    suspend fun getAllScannedUris(): List<String>
    /** 批量保存扫描结果：uri 对应 reason（null 表示已扫描但判定为正常照片） */
    suspend fun saveResults(results: List<Pair<String, String?>>)
    /** 用户确认删除废片原文件后，移除对应记录 */
    suspend fun removeResults(uris: List<String>)
    /** 清空所有扫描记录，用于"重新扫描全部"入口 */
    suspend fun clearAll()
}
