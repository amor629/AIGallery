package com.example.aigallery.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WastePhotoDao {

    /** 批量写入扫描结果（含"正常照片"标记），已存在则覆盖 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<WastePhotoEntity>)

    /** 响应式返回所有判定为废片的记录，供 UI 实时展示（Worker 每写入一批就会自动推送更新） */
    @Query("SELECT * FROM waste_photos WHERE reason IS NOT NULL ORDER BY scannedAt DESC")
    fun getWasteResults(): Flow<List<WastePhotoEntity>>

    /** 所有已扫描过的 URI（无论是否判定为废片），供 Worker 跳过重复扫描用 */
    @Query("SELECT uri FROM waste_photos")
    suspend fun getAllScannedUris(): List<String>

    /** 用户确认删除废片原文件后调用，清除对应记录 */
    @Query("DELETE FROM waste_photos WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    /** 清空所有扫描记录（"重新扫描全部"入口用） */
    @Query("DELETE FROM waste_photos")
    suspend fun clearAll()
}
