package com.example.aigallery.di

import javax.inject.Qualifier

/**
 * 应用级协程作用域限定符
 *
 * 用于区分 Hilt 注入的 CoroutineScope：
 * - @ApplicationScope → 与应用生命周期绑定，在 Application 销毁前不会被取消
 *
 * 使用场景：Repository 的 init 块、需要跨越 ViewModel 生命周期的后台任务
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
