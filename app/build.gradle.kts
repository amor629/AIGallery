// ============================================================
// app/build.gradle.kts — 应用模块构建配置
// 核心依赖全部在此声明，版本号统一由 gradle/libs.versions.toml 管理
// ============================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Kotlin 2.0+ 独立 Compose 编译器插件（替代旧的 composeOptions.kotlinCompilerExtensionVersion）
    alias(libs.plugins.kotlin.compose)
    // Hilt 依赖注入
    alias(libs.plugins.hilt)
    // KSP 代码生成（Room + Hilt 注解处理）
    alias(libs.plugins.ksp)
}

android {
    // 包名，也是 Play Store 的唯一标识，发布前可改为你自己的包名
    namespace = "com.example.aigallery"
    // compileSdk 35 = Android 15，可使用最新 API
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.aigallery"
        // ⚠️ 架构约束：最低支持 Android 14（API 34），充分利用新版媒体权限
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        // Release 构建：开启代码混淆和资源压缩，减小 APK 体积
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Debug 构建：关闭混淆，方便调试；添加 .debug 后缀，可与 Release 共存于同一设备
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        // 使用 Java 17 字节码，与现代 Kotlin 和 Jetpack 库对齐
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // 启用 Jetpack Compose
        compose = true
    }

    // 指定 Kotlin 源码目录（使用 kotlin/ 而非 java/）
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }

    packaging {
        resources {
            // 排除重复的许可证文件，避免打包冲突
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // ========== AndroidX 核心 ==========
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // ========== Jetpack Compose ==========
    // BOM 确保所有 Compose 子库版本一致，避免兼容性问题
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Material Icons 扩展包（包含更多图标，体积较大，Release 中 R8 会自动 Tree-shake 未使用部分）
    implementation(libs.androidx.compose.material.icons)

    // ========== Lifecycle + ViewModel ==========
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ========== 导航 ==========
    implementation(libs.androidx.navigation.compose)

    // ========== 图片加载 Coil 3 ==========
    // Compose 集成
    implementation(libs.coil.compose)
    // OkHttp 网络请求支持（Coil 从网络加载图片时使用；AI 可选模块也复用此 OkHttp 实例）
    implementation(libs.coil.okhttp)
    // 视频缩略图解码
    implementation(libs.coil.video)

    // ========== 视频播放 Media3 ==========
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // ========== 分页加载 Paging 3 ==========
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    // ========== 本地数据库 Room ==========
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)          // 协程扩展
    implementation(libs.room.paging)       // Paging 3 集成
    ksp(libs.room.compiler)                // 注解处理器（编译时生成 DAO 实现）

    // ========== 本地配置 DataStore ==========
    // 存储用户偏好：主题、排序方式等
    implementation(libs.datastore.preferences)

    // ========== 加密存储 ==========
    // 用于 AiConfigRepository：保护用户自填的 AI API Key，绝不明文落盘
    implementation(libs.security.crypto)

    // ========== 依赖注入 Hilt ==========
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)                // 注解处理器（编译时生成注入代码）
    implementation(libs.hilt.navigation.compose)  // 与 Compose Navigation 集成

    // ========== 网络（AI 可选模块）==========
    // ⚠️ 这些库仅在 AI 模块内使用，未配置 Key 时网络请求永远不会发出
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)    // Debug 构建下打印请求日志
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)

    // ========== 协程 ==========
    implementation(libs.kotlinx.coroutines.android)

    // ========== 测试 ==========
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Debug 专属：Compose 布局检查器和测试 Manifest
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
