package com.example.aigallery.domain.model

import android.net.Uri

/**
 * 相册（文件夹）领域模型
 *
 * 对应手机上的一个文件夹（如"相机"、"截图"、"微信"等）。
 * 数据来源：MediaStore 的 BUCKET_ID / BUCKET_DISPLAY_NAME 字段。
 *
 * @param id            相册唯一 ID（对应 MediaStore BUCKET_ID）
 * @param name          相册名称（对应 MediaStore BUCKET_DISPLAY_NAME）
 * @param coverUri      封面图 URI（该相册最新一张媒体的 URI）
 * @param mediaCount    相册内媒体总数（图片 + 视频）
 * @param lastModified  相册内最新媒体的添加时间（毫秒时间戳）
 */
data class Album(
    val id: Long,
    val name: String,
    val coverUri: Uri,
    val mediaCount: Int,
    val lastModified: Long
)
