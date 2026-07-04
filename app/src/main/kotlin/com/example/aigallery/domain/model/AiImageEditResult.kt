package com.example.aigallery.domain.model

import android.graphics.Bitmap

/**
 * AI 图片编辑（抠图/消除/修复/美化）的结果状态
 *
 * ⚠️ 设计要点：AI 处理完成后不会自动保存，而是进入 [ReadyToSave]，
 * 由用户在弹窗中选择"另存为新图片"或"覆盖原图"后才真正写入相册。
 */
sealed interface AiImageEditResult {
    data object Idle : AiImageEditResult
    data object Loading : AiImageEditResult

    /** AI 已生成结果图，等待用户选择保存方式 */
    data class ReadyToSave(
        val bitmap: Bitmap,
        val sourceUri: android.net.Uri,
        val editType: AiEditType
    ) : AiImageEditResult

    /** 用户已确认保存（另存为新图或覆盖原图）完成 */
    data class Saved(val message: String) : AiImageEditResult

    data class Error(val message: String) : AiImageEditResult
}
