package com.example.aigallery

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.example.aigallery.crash.GlobalCrashHandler
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用 Application 入口类
 *
 * @HiltAndroidApp 是 Hilt 依赖注入的触发点：
 * - 编译时 KSP 会根据此注解生成 Hilt 所需的依赖图代码
 * - 运行时 Hilt 在 Application.onCreate() 之前完成组件初始化
 */
@UnstableApi
@HiltAndroidApp
class AIGalleryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 注册全局异常捕获，必须在 Hilt 初始化完成后立即执行
        // 保存系统默认处理器作为兜底，我们的处理器处理完后若有需要可转交给它
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            GlobalCrashHandler(applicationContext, defaultHandler)
        )
    }
}
