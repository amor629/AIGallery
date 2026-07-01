package com.example.aigallery.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.aigallery.di.ApplicationScope
import com.example.aigallery.domain.model.AiConfig
import com.example.aigallery.domain.repository.IAiConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 配置 Repository 实现（Data 层）
 *
 * 使用 EncryptedSharedPreferences（security-crypto 库）存储 API Key 和 Base URL：
 * - 密钥由 Android Keystore 管理（AES-256-GCM 主密钥）
 * - SharedPreferences 的键和值分别用 AES256-SIV / AES256-GCM 加密
 * - 即使设备被 root，加密数据也无法被直接读取
 *
 * ⚠️ 安全约束（必须遵守）：
 *   1. 禁止将 apiKey 打印到 Logcat
 *   2. 禁止将 apiKey 传递给 Firebase Crashlytics 等第三方 SDK
 *   3. 禁止在任何地方硬编码默认 Key
 */
@Singleton
class AiConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope
) : IAiConfigRepository {

    companion object {
        // SharedPreferences 文件名（加密存储）
        private const val PREFS_FILE = "ai_config_secure"
        // 存储键名（键名本身也会被加密）
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY  = "api_key"
        // 连通性测试超时（秒）
        private const val CONNECTIVITY_TIMEOUT_SEC = 10L
    }

    // ---- 加密 SharedPreferences（懒加载，避免阻塞主线程）----
    private val encryptedPrefs: SharedPreferences by lazy {
        // 使用 Android Keystore 创建 AES-256-GCM 主密钥
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // 创建加密 SharedPreferences 实例
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ---- 内部可变 StateFlow，驱动响应式更新 ----
    private val _configState = MutableStateFlow<AiConfig?>(null)

    // ---- 对外暴露只读 Flow ----
    override val configFlow: Flow<AiConfig?> = _configState.asStateFlow()

    // ---- 当前配置的同步快照 ----
    override val currentConfig: AiConfig?
        get() = _configState.value

    init {
        // 应用启动时在 IO 线程读取已保存的配置，恢复上次状态
        scope.launch(Dispatchers.IO) {
            _configState.value = readFromPrefs()
        }
    }

    // ----------------------------------------------------------------
    // 私有方法：从加密 SharedPreferences 读取配置
    // ----------------------------------------------------------------
    private fun readFromPrefs(): AiConfig? {
        val baseUrl = encryptedPrefs.getString(KEY_BASE_URL, null)
        val apiKey  = encryptedPrefs.getString(KEY_API_KEY,  null)
        return if (!baseUrl.isNullOrBlank() && !apiKey.isNullOrBlank()) {
            AiConfig(baseUrl = baseUrl, apiKey = apiKey)
        } else {
            null
        }
    }

    // ----------------------------------------------------------------
    // 保存配置
    // ----------------------------------------------------------------
    override suspend fun saveConfig(config: AiConfig) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit()
                .putString(KEY_BASE_URL, config.baseUrl.trim())
                .putString(KEY_API_KEY,  config.apiKey.trim())
                .apply()
            // 更新内存中的 StateFlow，通知所有收集者（包括 AiStateManager）
            _configState.value = config
        }
    }

    // ----------------------------------------------------------------
    // 清除配置
    // ----------------------------------------------------------------
    override suspend fun clearConfig() {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit()
                .remove(KEY_BASE_URL)
                .remove(KEY_API_KEY)
                .apply()
            // 发出 null，触发 AiStateManager 切换到 NotConfigured 状态
            _configState.value = null
        }
    }

    // ----------------------------------------------------------------
    // 连通性测试
    // 向 baseUrl/models 发送 GET 请求，探测服务器是否可达
    // HTTP 200 或 401 均视为"可达"（401 说明 Key 可能有误，但服务器在线）
    // ----------------------------------------------------------------
    override suspend fun testConnectivity(): Result<Unit> = withContext(Dispatchers.IO) {
        val config = _configState.value
            ?: return@withContext Result.failure(IllegalStateException("尚未配置 AI，请先保存配置"))

        try {
            // 为测试创建独立的 OkHttpClient（短超时，不影响正常使用）
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECTIVITY_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(CONNECTIVITY_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()

            // 探测端点：OpenAI 兼容接口通常支持 GET /models
            val testUrl = config.baseUrl.trimEnd('/') + "/models"

            // ⚠️ Authorization header 中携带 apiKey，禁止打印此请求日志
            val request = Request.Builder()
                .url(testUrl)
                .header("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    // 2xx：服务器在线且 Key 有效
                    response.isSuccessful ->
                        Result.success(Unit)
                    // 401：服务器在线，但 API Key 无效
                    response.code == 401 ->
                        Result.failure(Exception("API 地址可达，但 API Key 验证失败 (HTTP 401)，请检查 Key 是否正确"))
                    // 其他错误码
                    else ->
                        Result.failure(Exception("服务器返回 HTTP ${response.code}，请检查 API 地址是否正确"))
                }
            }
        } catch (e: Exception) {
            // 网络异常、DNS 失败、超时等
            Result.failure(Exception("连接失败：${e.message}"))
        }
    }
}
