package com.example.aigallery.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 实体：截图 OCR 文本索引
 *
 * 一张照片最多一行（photoUri 唯一），持久化后作为「本地搜索索引」的一部分：
 * 搜索时可直接对已识别的文字做子串匹配，不必每次搜索都重新调用 AI 视觉扫描。
 */
@Entity(tableName = "photo_ocr")
data class PhotoOcrEntity(
    @PrimaryKey val photoUri: String,   // content://media/... URI 字符串
    val ocrText: String,
    val extractedAt: Long = System.currentTimeMillis()
)
