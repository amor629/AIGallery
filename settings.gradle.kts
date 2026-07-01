// ============================================================
// settings.gradle.kts — 项目级配置
// 定义插件仓库、依赖仓库，以及包含哪些模块
// ============================================================

pluginManagement {
    repositories {
        // Google 官方仓库（优先级最高，包含 Android/Jetpack 插件）
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS：禁止在子模块 build.gradle 中单独声明仓库，统一在此管理
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // gradle/libs.versions.toml 存在时 Gradle 8.x 会自动注册为 libs 目录
    // 无需手动 from()，否则会报 "from called more than once" 错误
}

rootProject.name = "AIGallery"
include(":app")
