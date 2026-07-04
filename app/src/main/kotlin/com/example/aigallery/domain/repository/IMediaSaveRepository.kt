package com.example.aigallery.domain.repository

import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri

/**
 * 将编辑后的媒体写回系统相册
 */
interface IMediaSaveRepository {

    /** 保存 Bitmap 为新的 JPEG/PNG 文件，返回新 MediaStore URI */
    suspend fun saveBitmap(
        bitmap: Bitmap,
        displayName: String,
        mimeType: String = "image/jpeg"
    ): Uri?

    /** 截取视频片段并保存为新文件 */
    suspend fun saveVideoTrim(
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        displayName: String
    ): Uri?

    /** 从 URI 加载 Bitmap（最长边限制，防止 OOM） */
    suspend fun loadBitmap(uri: Uri, maxDim: Int = 2048): Bitmap?

    /** 仅读取图片原始宽高（不加载像素），用于 AI 处理后按原始分辨率还原输出图片 */
    suspend fun readImageSize(uri: Uri): Pair<Int, Int>?

    /**
     * 构建"覆盖原图"所需的系统写入授权请求。
     * Android 11+ 对非本应用创建的媒体文件写入强制要求用户二次确认（类似删除确认）。
     *
     * @return 系统弹窗 IntentSender，传给 ActivityResultLauncher 启动；构建失败返回 null
     */
    suspend fun buildWriteRequest(uri: Uri): IntentSender?

    /** 用户授权后，将 bitmap 内容写回原 uri（覆盖原文件像素数据） */
    suspend fun overwriteBitmap(uri: Uri, bitmap: Bitmap, mimeType: String = "image/jpeg"): Boolean
}
