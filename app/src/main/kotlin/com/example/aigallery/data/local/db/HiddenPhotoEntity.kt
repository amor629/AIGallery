package com.example.aigallery.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 实体：隐藏相册索引
 *
 * "隐藏"照片会被物理移动到应用私有沙盒目录（外部无法通过 MediaStore 扫描到），
 * 因此原 MediaStore 记录随之消失——本表就是这些照片在"隐藏相册"里唯一的元数据来源，
 * 用于展示缩略图、排序，以及"恢复"时把文件重新写回系统相册所需的原始信息。
 */
@Entity(tableName = "hidden_photos")
data class HiddenPhotoEntity(
    /** 私有目录下的文件绝对路径，天然唯一，直接作为主键 */
    @PrimaryKey val hiddenFilePath: String,
    /** 原文件名，恢复时沿用 */
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    /** 原拍摄/添加时间（毫秒），隐藏相册排序 + 恢复后写回 DATE_TAKEN 用 */
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long = 0,
    /** 原所属相册名（仅展示用途，恢复后统一放入新相册，不强求还原到原相册） */
    val originalBucketName: String = "",
    val hiddenAt: Long = System.currentTimeMillis()
)
