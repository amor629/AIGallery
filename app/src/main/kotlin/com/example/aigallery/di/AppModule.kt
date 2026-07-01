package com.example.aigallery.di

import com.example.aigallery.data.mediastore.MediaStoreRepository
import com.example.aigallery.data.preferences.AiConfigRepository
import com.example.aigallery.domain.repository.IAiConfigRepository
import com.example.aigallery.domain.repository.IMediaRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块（应用级单例）
 *
 * 负责声明所有单例依赖的创建方式：
 * - ApplicationScope：与应用生命周期绑定的协程作用域
 * - IAiConfigRepository → AiConfigRepository（加密存储实现）
 *
 * 所有 @Singleton 对象在整个 App 生命周期内只创建一次。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    /**
     * 将接口 IAiConfigRepository 绑定到具体实现 AiConfigRepository
     * 上层（ViewModel、AiStateManager）只知道接口，不知道实现细节
     */
    @Binds
    @Singleton
    abstract fun bindAiConfigRepository(
        impl: AiConfigRepository
    ): IAiConfigRepository

    /**
     * 将接口 IMediaRepository 绑定到 MediaStoreRepository
     * ViewModel 只依赖接口，未来切换为网络相册或 Room 缓存时上层无需修改
     */
    @Binds
    @Singleton
    abstract fun bindMediaRepository(
        impl: MediaStoreRepository
    ): IMediaRepository

    companion object {

        /**
         * 提供应用级协程作用域
         *
         * - SupervisorJob：子协程失败不会取消其他子协程
         * - Dispatchers.Default：CPU 密集型默认调度器
         * - 此 Scope 永远不会被主动取消（与 Application 同生命周期）
         */
        @ApplicationScope
        @Provides
        @Singleton
        fun provideApplicationScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
