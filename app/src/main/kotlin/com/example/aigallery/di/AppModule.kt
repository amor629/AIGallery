package com.example.aigallery.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.example.aigallery.ai.AiApiClient
import com.example.aigallery.data.ai.AiChatService
import com.example.aigallery.data.ai.AiImageEditService
import com.example.aigallery.data.ai.AiImageRepositoryImpl
import com.example.aigallery.data.ai.AiSearchRepositoryImpl
import com.example.aigallery.data.local.db.AppDatabase
import com.example.aigallery.data.local.db.HiddenPhotoDao
import com.example.aigallery.data.local.db.PhotoOcrDao
import com.example.aigallery.data.local.db.PhotoTagDao
import com.example.aigallery.data.local.db.WastePhotoDao
import com.example.aigallery.data.local.hidden.HiddenPhotoRepositoryImpl
import com.example.aigallery.data.local.tag.TagRepositoryImpl
import com.example.aigallery.data.local.waste.WasteRepositoryImpl
import com.example.aigallery.data.ai.AiImageEditRepositoryImpl
import com.example.aigallery.data.mediastore.MediaSaveRepositoryImpl
import com.example.aigallery.data.mediastore.MediaStoreRepository
import com.example.aigallery.data.preferences.AiConfigRepository
import com.example.aigallery.data.preferences.AppPreferencesRepositoryImpl
import com.example.aigallery.data.preferences.ThemePreferencesRepository
import com.example.aigallery.domain.repository.IAppPreferencesRepository
import com.example.aigallery.domain.repository.IAiConfigRepository
import com.example.aigallery.domain.repository.IAiImageEditRepository
import com.example.aigallery.domain.repository.IAiImageRepository
import com.example.aigallery.domain.repository.IAiSearchRepository
import com.example.aigallery.domain.repository.IMediaSaveRepository
import com.example.aigallery.domain.repository.IHiddenPhotoRepository
import com.example.aigallery.domain.repository.IMediaRepository
import com.example.aigallery.domain.repository.ITagRepository
import com.example.aigallery.domain.repository.IThemeRepository
import com.example.aigallery.domain.repository.IWasteRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /**
     * 将接口 IAiSearchRepository 绑定到 AiSearchRepositoryImpl
     * GalleryViewModel 通过此接口调用 AI 自然语言检索解析
     */
    @Binds
    @Singleton
    abstract fun bindAiSearchRepository(
        impl: AiSearchRepositoryImpl
    ): IAiSearchRepository

    @Binds
    @Singleton
    abstract fun bindTagRepository(
        impl: TagRepositoryImpl
    ): ITagRepository

    @Binds
    @Singleton
    abstract fun bindAppPreferencesRepository(
        impl: AppPreferencesRepositoryImpl
    ): IAppPreferencesRepository

    /**
     * 将接口 IHiddenPhotoRepository 绑定到 HiddenPhotoRepositoryImpl
     * GalleryViewModel（隐藏操作）与 HiddenAlbumViewModel（浏览/恢复）共用此接口
     */
    @Binds
    @Singleton
    abstract fun bindHiddenPhotoRepository(
        impl: HiddenPhotoRepositoryImpl
    ): IHiddenPhotoRepository

    /**
     * 将接口 IWasteRepository 绑定到 WasteRepositoryImpl
     * WasteScanWorker（后台扫描写入）与 WasteCleanupViewModel（UI 读取）共用此接口
     */
    @Binds
    @Singleton
    abstract fun bindWasteRepository(
        impl: WasteRepositoryImpl
    ): IWasteRepository

    @Binds
    @Singleton
    abstract fun bindMediaSaveRepository(
        impl: MediaSaveRepositoryImpl
    ): IMediaSaveRepository

    @Binds
    @Singleton
    abstract fun bindAiImageEditRepository(
        impl: AiImageEditRepositoryImpl
    ): IAiImageEditRepository

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

        /**
         * 提供 AiImageEditService（Retrofit 动态代理）
         * 注入到 [AiImageEditRepositoryImpl]，用于老照片修复/AI美化
         */
        @Provides
        @Singleton
        fun provideAiImageEditService(retrofit: Retrofit): AiImageEditService =
            retrofit.create(AiImageEditService::class.java)

        /** Room 数据库（单例） */
        @Provides
        @Singleton
        fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "aigallery.db")
                .fallbackToDestructiveMigration()
                .build()

        /** PhotoTag DAO（从数据库实例获取） */
        @Provides
        @Singleton
        fun providePhotoTagDao(db: AppDatabase): PhotoTagDao = db.photoTagDao()

        /** PhotoOcr DAO（从数据库实例获取，持久化截图 OCR 文本用于本地搜索） */
        @Provides
        @Singleton
        fun providePhotoOcrDao(db: AppDatabase): PhotoOcrDao = db.photoOcrDao()

        /** HiddenPhoto DAO（从数据库实例获取，隐藏相册索引） */
        @Provides
        @Singleton
        fun provideHiddenPhotoDao(db: AppDatabase): HiddenPhotoDao = db.hiddenPhotoDao()

        /** WastePhoto DAO（从数据库实例获取，后台废片扫描记录） */
        @Provides
        @Singleton
        fun provideWastePhotoDao(db: AppDatabase): WastePhotoDao = db.wastePhotoDao()

        /** WorkManager 单例（供 SettingsViewModel 调度打标任务使用） */
        @Provides
        @Singleton
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
            WorkManager.getInstance(context)
    }
}
