package com.example.aigallery.domain.model

/**
 * AI 智能分类使用的固定标签体系（闭集，避免过度细分导致同一张照片打上多个高度相似的标签）。
 *
 * 后台打标（[com.example.aigallery.work.PhotoTagWorker]）与搜索时的"即时打标兜底"
 * （[com.example.aigallery.ui.gallery.GalleryViewModel]）共用同一份列表，
 * 避免两处硬编码内容不同步导致标签体系漂移、搜索命中失效。
 */
object PhotoTagTaxonomy {
    val CATEGORIES: List<String> = listOf(
        "人像/自拍", "风景", "建筑", "美食", "宠物", "植物", "旅行", "运动", "证件文档",
        "电子设备", "聊天记录", "短信", "支付订单", "二维码", "社交媒体", "游戏画面",
        "影视截图", "其他截图", "其他"
    )
}
