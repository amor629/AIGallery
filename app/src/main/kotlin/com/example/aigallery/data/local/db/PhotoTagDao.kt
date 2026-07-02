package com.example.aigallery.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 标签相册聚合结果（标签名 + 数量 + 封面 URI） */
data class TagAlbumRow(
    val tag: String,
    val count: Int,
    val coverUri: String
)

@Dao
interface PhotoTagDao {

    /** 批量插入标签，已存在则忽略（防止重复打标） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<PhotoTagEntity>)

    /** 以响应式 Flow 返回所有标签相册（按数量降序），每次 DB 变更自动推送 */
    @Query("""
        SELECT tag, COUNT(*) AS count, MIN(photoUri) AS coverUri
        FROM photo_tags
        GROUP BY tag
        ORDER BY count DESC
    """)
    fun getTagAlbums(): Flow<List<TagAlbumRow>>

    /** 根据标签获取对应的所有照片 URI */
    @Query("SELECT DISTINCT photoUri FROM photo_tags WHERE tag = :tag ORDER BY taggedAt DESC")
    fun getPhotoUrisByTag(tag: String): Flow<List<String>>

    /** 查询所有已打标照片的 URI（用于 Worker 跳过已处理图片） */
    @Query("SELECT DISTINCT photoUri FROM photo_tags")
    suspend fun getAllTaggedUris(): List<String>

    /** 返回标签总数（用于 Settings 页展示进度） */
    @Query("SELECT COUNT(DISTINCT photoUri) FROM photo_tags")
    fun getTaggedPhotoCount(): Flow<Int>

    /** 清空所有标签数据 */
    @Query("DELETE FROM photo_tags")
    suspend fun clearAll()
}
