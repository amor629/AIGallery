package com.example.aigallery.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体：照片标签映射
 * 一张照片可有多行（每个标签一行），通过 URI + tag 联合唯一性靠 IGNORE 冲突策略保证
 */
@Entity(
    tableName = "photo_tags",
    indices = [Index("photoUri"), Index("tag")]
)
data class PhotoTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoUri: String,   // content://media/... URI 字符串
    val tag: String,        // 标签名，如 "美食"
    val taggedAt: Long = System.currentTimeMillis()
)
