package com.example.aigallery.data.ai

import com.example.aigallery.ai.AiApiClient
import com.example.aigallery.data.ai.dto.AiChatRequest
import com.example.aigallery.data.ai.dto.AiContentPart
import com.example.aigallery.data.ai.dto.AiMessage
import com.example.aigallery.domain.model.SearchCriteria
import com.example.aigallery.domain.model.SearchMediaType
import com.example.aigallery.domain.repository.IAiSearchRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 自然语言检索解析 Repository 实现（Data 层）
 *
 * 流程：
 * 1. 检查 AI 是否已配置（未配置直接返回 Failure）
 * 2. 将当前时间 + 用户查询文本打包为 Prompt 发给 LLM
 * 3. LLM 返回 JSON 格式的结构化检索条件
 * 4. 解析 JSON → [SearchCriteria]
 *
 * ⚠️ 隐私安全：
 * - 仅发送文字（当前时间 + 用户查询），绝对不上传任何图片
 * - 所有实际的媒体过滤在设备本地完成
 *
 * ⚠️ 异常处理：
 * - 所有异常在此类内部捕获并转换为 [Result.failure]，不向外抛出
 */
@Singleton
class AiSearchRepositoryImpl @Inject constructor(
    private val aiApiClient: AiApiClient,
    private val aiChatService: AiChatService,
    private val gson: Gson
) : IAiSearchRepository {

    companion object {
        /** 用于搜索意图解析的文本模型（与图片识别共用同一配置的端点） */
        private const val MODEL_NAME = "qwen-max"

        /** AI 返回 JSON 的最大 Token 数（检索条件结构简单，256 已充足） */
        private const val MAX_TOKENS = 256
    }

    // ----------------------------------------------------------------
    // IAiSearchRepository 实现
    // ----------------------------------------------------------------

    override suspend fun parseQuery(query: String, nowMillis: Long): Result<SearchCriteria> {
        return withContext(Dispatchers.IO) {
            try {
                // ① 检查 AI 配置
                val baseUrl = try {
                    aiApiClient.requireBaseUrl()
                } catch (e: AiApiClient.AiNotConfiguredException) {
                    return@withContext Result.failure(
                        IllegalStateException("AI 未配置，请在设置页填写 API 地址和 Key")
                    )
                }

                // ② 构建包含当前时间的 Prompt
                val prompt = buildPrompt(query, nowMillis)

                // ③ 拼接端点 URL（与图片识别共用同一个 chat/completions 路径）
                val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

                // ④ 构建纯文本请求（不含图片，保护用户隐私）
                val request = AiChatRequest(
                    model = MODEL_NAME,
                    maxTokens = MAX_TOKENS,
                    messages = listOf(
                        AiMessage(
                            role = "user",
                            content = listOf(
                                AiContentPart(type = "text", text = prompt)
                            )
                        )
                    )
                )

                // ⑤ 发起网络请求
                val response = aiChatService.chatCompletion(endpoint, request)

                // ⑥ 解析响应
                val content = response.choices?.firstOrNull()?.message?.content
                    ?: return@withContext Result.failure(
                        IOException("AI 返回了空响应，请重试")
                    )

                // ⑦ 从 AI 回答中提取 JSON 并转换为 SearchCriteria
                val criteria = parseJsonToCriteria(content, nowMillis)
                Result.success(criteria)

            } catch (e: HttpException) {
                // HTTP 层错误（4xx / 5xx）
                val msg = when (e.code()) {
                    401  -> "API Key 无效或已过期，请在设置页重新配置"
                    429  -> "请求过于频繁，请稍后再试（限流）"
                    else -> "服务器错误 (HTTP ${e.code()})，请稍后重试"
                }
                Result.failure(IOException(msg))
            } catch (e: SocketTimeoutException) {
                Result.failure(IOException("请求超时，请检查网络连接后重试"))
            } catch (e: IOException) {
                Result.failure(IOException("网络错误：${e.message}"))
            } catch (e: Exception) {
                Result.failure(IOException("未知错误：${e.message}"))
            }
        }
    }

    // ----------------------------------------------------------------
    // 私有：构建 Prompt
    // ----------------------------------------------------------------

    /**
     * 构建发送给 LLM 的 Prompt
     *
     * 包含：
     * - 当前精确时间（让 AI 能正确解析"昨天"、"上个月"等相对日期）
     * - 用户查询文本
     * - 严格 JSON 输出格式要求
     *
     * @param query      用户查询文本
     * @param nowMillis  当前时间毫秒时间戳
     */
    private fun buildPrompt(query: String, nowMillis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowStr = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)
            .format(Date(nowMillis))
        val year  = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1  // Calendar.MONTH 从 0 开始

        return """
你是一个手机相册搜索助手。当前时间是 $nowStr。

用户想搜索手机相册，查询内容是："$query"

请将该查询解析为以下 JSON 格式（只输出 JSON，不要任何解释或 Markdown 代码块）：
{
  "mediaType": "ALL",
  "dateFrom": null,
  "dateTo": null,
  "filenameKeywords": [],
  "bucketNameKeywords": []
}

字段说明：
- mediaType: 字符串，只能是 "ALL"、"IMAGE"、"VIDEO" 之一
  - 用户说"视频"、"录像"、"影片" → "VIDEO"
  - 用户说"照片"、"图片"、"相片"、"截图" → "IMAGE"
  - 未明确指定 → "ALL"
- dateFrom / dateTo: 字符串（"YYYY-MM-DD"格式）或 null
  - "今天" → dateFrom=今天0点, dateTo=今天23:59
  - "昨天" → dateFrom=昨天0点, dateTo=昨天23:59
  - "本月" / "这个月" → dateFrom=${year}年${month}月1日, dateTo=本月最后一天
  - "上个月" → 上月1日到上月最后一天
  - "今年" → ${year}-01-01 到 ${year}-12-31
  - "去年" → ${year - 1}-01-01 到 ${year - 1}-12-31
  - "YYYY年" → 该年1月1日到12月31日
  - "YYYY年M月" → 该月第1天到最后一天
  - 无时间信息 → null
- filenameKeywords: 数组，可能出现在文件名中的关键词（英文优先）
  - "截图" → ["Screenshot", "screenshot"]
  - "相机" / "拍的" → ["IMG_", "PXL_", "DCIM"]
  - 无关 → []
- bucketNameKeywords: 数组，相册目录名关键词
  - "截图" → ["Screenshots", "截图", "Screenshot"]
  - "相机" / "拍的" → ["Camera", "DCIM"]
  - "微信" → ["微信", "WeChat", "Tencent"]
  - "下载" → ["Download", "下载"]
  - 无关 → []

只输出纯 JSON，不要包含任何其他内容。
        """.trimIndent()
    }

    // ----------------------------------------------------------------
    // 私有：解析 AI 返回的 JSON 为 SearchCriteria
    // ----------------------------------------------------------------

    /**
     * 从 AI 的文本回复中提取 JSON 并解析为 [SearchCriteria]
     *
     * 容错处理：
     * - AI 可能在 JSON 外包裹 Markdown 代码块（```json ... ```），会先剥除
     * - 日期字符串解析失败时跳过对应字段（不影响其他条件）
     * - 任何 JSON 解析错误都 fallback 为空条件（全量显示）
     *
     * @param aiResponse  AI 返回的原始文本
     * @param nowMillis   当前时间（用于补全无时区信息的日期）
     */
    private fun parseJsonToCriteria(aiResponse: String, nowMillis: Long): SearchCriteria {
        return try {
            // 剥除可能存在的 Markdown 代码块包装
            val jsonStr = extractJson(aiResponse)

            val obj: JsonObject = JsonParser.parseString(jsonStr).asJsonObject

            // 解析 mediaType
            val mediaType = when (
                obj.get("mediaType")?.asString?.uppercase()
            ) {
                "IMAGE" -> SearchMediaType.IMAGE
                "VIDEO" -> SearchMediaType.VIDEO
                else    -> SearchMediaType.ALL
            }

            // 解析日期（"YYYY-MM-DD" → 毫秒时间戳）
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dateFrom = obj.get("dateFrom")?.let { el ->
                if (el.isJsonNull) null
                else runCatching { sdf.parse(el.asString)?.time }.getOrNull()
            }
            val dateTo = obj.get("dateTo")?.let { el ->
                if (el.isJsonNull) null
                else runCatching {
                    // dateTo 表示当天结束（23:59:59），加上一天毫秒数后减 1ms
                    sdf.parse(el.asString)?.let { it.time + 24 * 60 * 60 * 1000L - 1L }
                }.getOrNull()
            }

            // 解析关键词数组
            val filenameKeywords = obj.getAsJsonArray("filenameKeywords")
                ?.mapNotNull { it.asString.takeIf { s -> s.isNotBlank() } }
                ?: emptyList()

            val bucketNameKeywords = obj.getAsJsonArray("bucketNameKeywords")
                ?.mapNotNull { it.asString.takeIf { s -> s.isNotBlank() } }
                ?: emptyList()

            SearchCriteria(
                mediaType          = mediaType,
                dateFrom           = dateFrom,
                dateTo             = dateTo,
                filenameKeywords   = filenameKeywords,
                bucketNameKeywords = bucketNameKeywords,
            )
        } catch (e: Exception) {
            // JSON 解析失败：返回空条件（等价于全量匹配，不崩溃）
            android.util.Log.w("AiSearch", "JSON 解析失败，降级为空条件: ${e.message}")
            SearchCriteria()
        }
    }

    /**
     * 从可能含 Markdown 代码块的字符串中提取纯 JSON
     *
     * 支持以下格式：
     * - 直接 JSON：`{ ... }`
     * - Markdown 代码块：` ```json\n{ ... }\n``` `
     */
    private fun extractJson(text: String): String {
        // 先尝试剥除 ```json ... ``` 或 ``` ... ``` 包装
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = codeBlockRegex.find(text.trim())
        if (match != null) return match.groupValues[1].trim()

        // 如果没有代码块，尝试提取第一个 { ... } 块
        val braceStart = text.indexOf('{')
        val braceEnd   = text.lastIndexOf('}')
        if (braceStart != -1 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1)
        }

        return text.trim()
    }
}
