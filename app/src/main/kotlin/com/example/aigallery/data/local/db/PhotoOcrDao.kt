package com.example.aigallery.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PhotoOcrDao {

    /** 插入或覆盖某张照片的 OCR 文本（同一张照片重新打标时以最新识别结果为准） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PhotoOcrEntity)

    /** 本地子串检索：OCR 文本包含查询词的所有照片 URI（免费、瞬时，替代实时 AI 扫描） */
    @Query("SELECT DISTINCT photoUri FROM photo_ocr WHERE ocrText LIKE '%' || :query || '%'")
    suspend fun searchByText(query: String): List<String>

    /** 清空所有 OCR 文本数据（配合"清空重新分类"一起清空） */
    @Query("DELETE FROM photo_ocr")
    suspend fun clearAll()
}
