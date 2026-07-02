package com.example.aigallery.domain.model

/**
 * 废片分析结果（Domain 层模型）
 *
 * @param mediaItem 废片对应的媒体文件元数据
 * @param reason    废片原因，由视觉 AI 返回（如"模糊"/"闭眼"/"重复"/"截图"）
 */
data class WastePhoto(
    val mediaItem: MediaItem,
    val reason: String
)
