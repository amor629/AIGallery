package com.example.aigallery.ai

import com.example.aigallery.domain.repository.IAiConfigRepository
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 网络客户端（动态配置版）
 *
 * ⚠️ 核心设计原则：
 * 1. Base URL 和 API Key 在每次请求前从 [IAiConfigRepository] 动态读取
 *    → 用户修改配置后，下一个请求立即生效，无需重启应用
 * 2. 未配置时调用任何方法都会抛出 [AiNotConfiguredException]，调用方负责捕获
 * 3. API Key 绝对不会出现在日志中（日志拦截器会自动脱敏）
 *
 * 使用方式：
 *   在 Stage 3 阶段，AI 功能 UseCase 会通过此客户端发起请求。
 *   当前为基础骨架，具体 AI API 方法在 Stage 3 补充。
 */
@Singleton
class AiApiClient @Inject constructor(
    private val repository: IAiConfigRepository
) {
    // ---- 自定义异常：AI 未配置时调用网络方法抛出 ----
    class AiNotConfiguredException :
        IllegalStateException("AI 功能未配置，请在设置页填写 API Key 和 Base URL")

    // ---- OkHttpClient（懒加载，首次 AI 请求时才创建）----
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // AI 推理可能需要较长时间
            .writeTimeout(30, TimeUnit.SECONDS)
            // 认证拦截器：每次请求前动态读取 API Key
            .addInterceptor(createAuthInterceptor())
            // 日志拦截器（仅 Debug 构建，且自动脱敏 Authorization header）
            .addInterceptor(createSafeLoggingInterceptor())
            .build()
    }

    /**
     * 获取当前配置的 Base URL（如未配置则抛出异常）
     * Retrofit 动态 baseUrl 时使用此方法
     */
    fun requireBaseUrl(): String =
        repository.currentConfig?.baseUrl?.trimEnd('/')?.plus("/")
            ?: throw AiNotConfiguredException()

    // ----------------------------------------------------------------
    // 认证拦截器：在请求头中注入 Bearer Token
    // ⚠️ 此处读取 apiKey，但绝不打印到任何日志
    // ----------------------------------------------------------------
    private fun createAuthInterceptor() = Interceptor { chain ->
        val config = repository.currentConfig
            ?: throw AiNotConfiguredException()

        val original = chain.request()
        val builder = original.newBuilder()
            .header("Authorization", "Bearer ${config.apiKey}")

        // ⚠️ 仅在请求体没有自带 Content-Type 时才补充默认的 JSON 类型，
        // 避免误覆盖某些请求（如 multipart/form-data）自带的 boundary 类型。
        if (original.body?.contentType() == null) {
            builder.header("Content-Type", "application/json")
        }

        chain.proceed(builder.build())
    }

    // ----------------------------------------------------------------
    // 安全日志拦截器：脱敏 Authorization header，防止 Key 泄漏到 Logcat
    // ----------------------------------------------------------------
    private fun createSafeLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            // 过滤掉包含 Authorization 的行（防止 Key 打印到 Logcat）
            if (!message.startsWith("Authorization:")) {
                android.util.Log.d("AiApiClient", message)
            }
        }.apply {
            // 仅记录请求行和响应码，不记录 Body（Body 可能包含敏感信息）
            level = HttpLoggingInterceptor.Level.BASIC
        }
    }

    // ----------------------------------------------------------------
    // TODO Stage 3：在此添加具体的 AI API 方法
    // 示例：
    //   suspend fun generateCaption(imageBase64: String): String
    //   suspend fun semanticSearch(query: String): List<SearchResult>
    // ----------------------------------------------------------------
}
