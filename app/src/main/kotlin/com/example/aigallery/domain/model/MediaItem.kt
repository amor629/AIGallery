package com.example.aigallery.domain.model

import android.net.Uri

/**
 * 媒体文件类型
 */
enum class MediaType {
    IMAGE,  // 图片
    VIDEO   // 视频
}

/**
 * 媒体文件领域模型（Domain 层）
 *
 * ⚠️ 内存安全原则：
 *   此模型只存储文件的元数据，不包含任何像素数据（Bitmap）。
 *   图片内容由 UI 层的 Coil3 按需加载，显示时才占用内存，滑出屏幕后自动释放。
 *
 * @param id          MediaStore 中的唯一 ID（用于构建 ContentUri）
 * @param uri         内容 URI（content://media/...），供 Coil3 加载缩略图
 * @param name        文件名（如 IMG_20240101_120000.jpg）
 * @param dateAdded   加入媒体库的时间（毫秒时间戳，来自 DATE_ADDED×1000）
 * @param dateTaken   实际拍摄/录制时间（毫秒时间戳，来自 EXIF/元数据）
 * @param mimeType    MIME 类型（如 image/jpeg、video/mp4）
 * @param mediaType   枚举：IMAGE 或 VIDEO
 * @param width       媒体宽度（像素），未知时为 0
 * @param height      媒体高度（像素），未知时为 0
 * @param size        文件大小（字节）
 * @param duration    视频时长（毫秒），图片为 0
 * @param bucketId    所属相册（文件夹）的 ID
 * @param bucketName  所属相册（文件夹）的名称
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateAdded: Long,
    val dateTaken: Long,
    val mimeType: String,
    val mediaType: MediaType,
    val width: Int,
    val height: Int,
    val size: Long,
    val duration: Long,
    val bucketId: Long,
    val bucketName: String,
) {
    /**
     * 是否为截图（根据所属文件夹名称判断，兼容中英文系统路径）
     */
    val isScreenshot: Boolean get() =
        bucketName.lowercase().let { it.contains("screenshot") || it.contains("截图") }

    /**
     * 用于时间轴分组的日期键（格式：yyyy-MM，精确到月）
     * GalleryScreen 按此字段分组展示"XX年X月"的分组标题
     */
    val monthKey: String
        get() {
            val time = if (dateTaken > 0) dateTaken else dateAdded
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = time }
            return "${cal.get(java.util.Calendar.YEAR)}-${
                (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
            }"
        }
}
