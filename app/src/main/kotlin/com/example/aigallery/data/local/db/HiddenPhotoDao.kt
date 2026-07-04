package com.example.aigallery.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenPhotoDao {

    /** 批量写入隐藏记录（系统删除原文件确认后调用） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<HiddenPhotoEntity>)

    /** 隐藏相册列表，按隐藏时间降序，响应式（恢复/新增后自动刷新） */
    @Query("SELECT * FROM hidden_photos ORDER BY hiddenAt DESC")
    fun getAll(): Flow<List<HiddenPhotoEntity>>

    /** 已隐藏照片数量（供设置页/入口角标展示，可选使用） */
    @Query("SELECT COUNT(*) FROM hidden_photos")
    fun getCount(): Flow<Int>

    /** 恢复流程：按私有文件路径批量查出待恢复记录 */
    @Query("SELECT * FROM hidden_photos WHERE hiddenFilePath IN (:paths)")
    suspend fun getByPaths(paths: List<String>): List<HiddenPhotoEntity>

    /** 恢复成功后删除对应记录 */
    @Query("DELETE FROM hidden_photos WHERE hiddenFilePath = :path")
    suspend fun deleteByPath(path: String)
}
