package com.example.aigallery.di

import com.example.aigallery.ai.AiApiClient
import com.example.aigallery.data.ai.AiChatService
import com.example.aigallery.data.ai.AiImageRepositoryImpl
import com.example.aigallery.data.mediastore.MediaStoreRepository
import com.example.aigallery.data.preferences.AiConfigRepository
import com.example.aigallery.data.preferences.ThemePreferencesRepository
import com.example.aigallery.domain.repository.IAiConfigRepository
import com.example.aigallery.domain.repository.IAiImageRepository
import com.example.aigallery.domain.repository.IMediaRepository
import com.example.aigallery.domain.repository.IThemeRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

    /**
     * 将接口 IAiImageRepository 绑定到 AiImageRepositoryImpl
     * PhotoDetailScreen 的 AiDetailViewModel 通过此接口调用 AI 分析
     */
    @Binds
    @Singleton
    abstract fun bindAiImageRepository(
        impl: AiImageRepositoryImpl
    ): IAiImageRepository

    /**
     * 将接口 IThemeRepository 绑定到 ThemePreferencesRepository
     * 主题切换用：SettingsViewModel 读写，MainActivity 订阅 Flow
     */
    @Binds
    @Singleton
    abstract fun bindThemeRepository(
        impl: ThemePreferencesRepository
    ): IThemeRepository

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

        /**
         * 提供 Gson 实例（用于 Retrofit JSON 序列化 / 反序列化）
         * serializeNulls = false：跳过 null 字段，减少请求体体积
         */
        @Provides
        @Singleton
        fun provideGson(): Gson = GsonBuilder().create()

        /**
         * 提供 Retrofit 实例
         *
         * baseUrl 使用占位地址；实际请求通过 @Url 动态覆盖，
         * 这样用户修改 API 地址后无需重建 Retrofit。
         *
         * OkHttpClient 来自 [AiApiClient]，已内置：
         *   - 认证拦截器（动态注入 Bearer Token）
         *   - 安全日志拦截器（蓉闐 Authorization header）
         *   - 超时配置（30s 连接 / 60s 读取）
         */
        @Provides
        @Singleton
        fun provideRetrofit(aiApiClient: AiApiClient, gson: Gson): Retrofit =
            Retrofit.Builder()
                .baseUrl("https://dashscope.aliyuncs.com/")  // 占位；被 @Url 覆盖
                .client(aiApiClient.httpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()

        /**
         * 提供 AiChatService（Retrofit 动态代理）
         * 注入到 [AiImageRepositoryImpl]，调用时动态传入完整 URL
         */
        @Provides
        @Singleton
        fun provideAiChatService(retrofit: Retrofit): AiChatService =
            retrofit.create(AiChatService::class.java)
    }
}
