package com.example.aigallery.domain.model

/**
 * AI 编辑功能类型（详情页「AI 编辑」菜单项）
 */
enum class AiEditType(val label: String) {
    CAPTION("AI 生成文案"),
    RECOGNIZE("AI 识图"),
    RESTORE("AI 老照片修复"),
    BEAUTIFY("AI 照片美化")
}

/**
 * 本地编辑模式（详情页底部工具栏）
 */
enum class LocalEditMode(val label: String) {
    MOSAIC("打马赛克"),
    CROP("图片裁剪"),
    DOODLE("涂鸦")
}
