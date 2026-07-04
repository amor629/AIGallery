package com.example.aigallery.domain.repository

import android.net.Uri
import com.example.aigallery.domain.model.AiEditType
import com.example.aigallery.domain.model.AiImageEditResult

/**
 * AI 图片编辑 Repository（老照片修复 / AI 照片美化）
 *
 * ⚠️ 架构约束：均需调用大模型（图片编辑类接口），不提供本地离线兜底算法。
 * 未配置 AI 或调用失败时返回 [AiImageEditResult.Error]，调用方无需 try-catch。
 */
interface IAiImageEditRepository {

    /**
     * 调用大模型处理图片，返回待用户确认保存的结果（不会自动落盘）
     *
     * @param uri  原图 URI
     * @param type 编辑类型（决定发送给模型的提示词）
     */
    suspend fun processImage(uri: Uri, type: AiEditType): AiImageEditResult
}
