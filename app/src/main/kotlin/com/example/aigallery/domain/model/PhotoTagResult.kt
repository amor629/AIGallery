package com.example.aigallery.domain.model

/** AI 批量打标结果：一张图片对应的 URI + 标签列表 */
data class PhotoTagResult(
    val uri: String,          // content:// URI 字符串
    val tags: List<String>    // 标签列表，如 ["美食", "餐厅"]
)
