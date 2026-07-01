package com.example.aigallery.domain.model

/**
 * 应用主题枚举
 *
 * - SYSTEM：跟随手机系统设置（默认值）
 * - LIGHT：始终使用亮色主题
 * - DARK：始终使用暗色主题
 */
enum class AppTheme {
    SYSTEM,   // 跟随系统
    LIGHT,    // 强制亮色
    DARK      // 强制暗色
}
