package com.example.aigallery.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 实体：废片扫描记录
 *
 * reason == null 表示该照片已被扫描过但判定为正常照片（用于幂等跳过，避免下次扫描
 * 重复消耗 AI 调用）；reason 非空表示废片，值为具体原因（模糊 / 闭眼 / 重复 / 截图）。
 */
@Entity(tableName = "waste_photos")
data class WastePhotoEntity(
    @PrimaryKey val uri: String,
    val reason: String?,
    val scannedAt: Long = System.currentTimeMillis()
)
