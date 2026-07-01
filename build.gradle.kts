// ============================================================
// build.gradle.kts（根项目）
// 顶层构建文件：只声明插件，不添加任何依赖
// 所有依赖都在 app/build.gradle.kts 中声明
// ============================================================

plugins {
    // apply false 表示：声明插件但不在根项目中应用，由子模块按需应用
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.android.library)      apply false
    alias(libs.plugins.kotlin.android)       apply false
    alias(libs.plugins.kotlin.compose)       apply false
    alias(libs.plugins.hilt)                 apply false
    alias(libs.plugins.ksp)                  apply false
}
