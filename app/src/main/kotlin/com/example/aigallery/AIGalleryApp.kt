package com.example.aigallery

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用 Application 入口类
 *
 * @HiltAndroidApp 是 Hilt 依赖注入的触发点：
 * - 编译时 KSP 会根据此注解生成 Hilt 所需的依赖图代码
 * - 运行时 Hilt 在 Application.onCreate() 之前完成组件初始化
 *
 * 后续阶段将在此注册全局单例（如 AiStateManager）
 */
@HiltAndroidApp
class AIGalleryApp : Application()
