package com.example.aigallery.domain.model

/** AI 批量打标结果：一张图片对应的 URI + 标签列表 + （截图专属）OCR 文本 */
data class PhotoTagResult(
    val uri: String,             // content:// URI 字符串
    val tags: List<String>,      // 标签列表，如 ["美食", "餐厅"]
    val ocrText: String? = null  // 截图的 OCR 识别文本，非截图为 null；持久化后用于本地搜索
)
