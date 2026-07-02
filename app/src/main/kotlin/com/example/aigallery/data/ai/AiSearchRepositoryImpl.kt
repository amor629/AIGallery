package com.example.aigallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.aigallery.ai.AiApiClient
import com.example.aigallery.data.ai.dto.AiChatRequest
import com.example.aigallery.data.ai.dto.AiContentPart
import com.example.aigallery.data.ai.dto.AiImageUrl
import com.example.aigallery.data.ai.dto.AiMessage
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.SearchCriteria
import com.example.aigallery.domain.model.SearchMediaType
import com.example.aigallery.domain.repository.IAiSearchRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 自然语言检�?+ 视觉内容搜索 Repository 实现（Data 层）
 *
 * 两大功能�?
 * 1. [parseQuery]：发送文本查询给 LLM �?解析为结构化 [SearchCriteria]（含 visualQuery�?
 * 2. [visualSearchBatch]：将图片批量发送给视觉 AI �?返回命中图片的下�?
 *
 * ⚠️ 安全约束�?
 * - API Key �?[AiApiClient] 拦截器注入，此类不直接接�?Key
 * - 所有异常在内部捕获，不向上传播
 */
@Singleton
class AiSearchRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiApiClient: AiApiClient,
    private val aiChatService: AiChatService,
    private val gson: Gson
) : IAiSearchRepository {

    companion object {
        private const val MODEL_TEXT   = "qwen-max"     // 文本意图解析
        private const val MODEL_VISUAL = "qwen-vl-max"  // 图片内容识别
        private const val MAX_TOKENS_TEXT   = 256
        private const val MAX_TOKENS_VISUAL = 50        // 只需返回编号数组
        private const val MAX_VISUAL_DIM    = 512       // 视觉搜索用较小缩略图
        private const val JPEG_QUALITY      = 75
    }

    // ============================================================
    // parseQuery：文本查�?�?结构化检索条�?
    // ============================================================

    override suspend fun parseQuery(query: String, nowMillis: Long): Result<SearchCriteria> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = try {
                    aiApiClient.requireBaseUrl()
                } catch (e: AiApiClient.AiNotConfiguredException) {
                    return@withContext Result.failure(
                        IllegalStateException("AI 未配置，请在设置页填�?API 地址�?Key")
                    )
                }
                val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
                val request = AiChatRequest(
                    model = MODEL_TEXT,
                    maxTokens = MAX_TOKENS_TEXT,
                    messages = listOf(
                        AiMessage(
                            role = "user",
                            content = listOf(AiContentPart(type = "text", text = buildTextPrompt(query, nowMillis)))
                        )
                    )
                )
                val response = aiChatService.chatCompletion(endpoint, request)
                val content  = response.choices?.firstOrNull()?.message?.content
                    ?: return@withContext Result.failure(IOException("AI 返回了空响应，请重试"))
                Result.success(parseJsonToCriteria(content, nowMillis))
            } catch (e: HttpException) {
                Result.failure(IOException(when (e.code()) {
                    401  -> "API Key 无效或已过期，请在设置页重新配置"
                    429  -> "请求过于频繁，请稍后再试（限流）"
                    else -> "服务器错�?(HTTP ${e.code()})，请稍后重试"
                }))
            } catch (e: SocketTimeoutException) { Result.failure(IOException("请求超时")) }
              catch (e: IOException)             { Result.failure(e) }
              catch (e: Exception)               { Result.failure(IOException(e.message)) }
        }
    }

    // ============================================================
    // visualSearchBatch：批量图片视觉内容匹�?
    // ============================================================

    override suspend fun visualSearchBatch(
        mediaItems: List<MediaItem>,
        visualQuery: String,
    ): List<Int> = withContext(Dispatchers.IO) {
        try {
            val endpoint = aiApiClient.requireBaseUrl().trimEnd('/') + "/chat/completions"

            val contentParts   = mutableListOf<AiContentPart>()
            val encodedIndices = mutableListOf<Int>()   // 记录哪些图片成功编码

            // �?将每张图片编码为 Base64
            for (i in mediaItems.indices) {
                val b64 = encodeImageToBase64(mediaItems[i].uri) ?: continue
                contentParts.add(
                    AiContentPart(type = "image_url", imageUrl = AiImageUrl("data:image/jpeg;base64,$b64"))
                )
                encodedIndices.add(i)
            }
            if (contentParts.isEmpty()) return@withContext emptyList()

            // �?附加问题文本（放在图片之后，部分多模态模型要求文本在最后）
            // 使用严格标准：要求图片核心主体是查询内容，防止因背景/次要元素导致误匹配
            val n = contentParts.size
            contentParts.add(
                AiContentPart(
                    type = "text",
                    text = "以上 $n 张图片按顺序编号 0 到 ${n - 1}。\n" +
                           "任务：找出主体内容**明确是**「$visualQuery」的图片。\n" +
                           "判断标准：图片的核心拍摄对象或场景主体必须是该内容；" +
                           "仅在背景中偶尔出现、或只是间接相关的，不算命中。\n" +
                           "只回答命中的编号，JSON 数组格式，例如 [0,2]；无匹配回答 []。不要输出任何解释。"
                )
            )

            val response = aiChatService.chatCompletion(
                endpoint,
                AiChatRequest(model = MODEL_VISUAL, maxTokens = MAX_TOKENS_VISUAL,
                    messages = listOf(AiMessage(role = "user", content = contentParts)))
            )
            val content = response.choices?.firstOrNull()?.message?.content
                ?: return@withContext emptyList()

            // �?解析 "[0, 2]" �?真实 MediaItem 下标
            parseIndexArray(content).mapNotNull { pos ->
                if (pos in encodedIndices.indices) encodedIndices[pos] else null
            }

        } catch (e: AiApiClient.AiNotConfiguredException) { emptyList() }
          catch (e: Exception) {
              android.util.Log.w("AiVisualSearch", "批次识别失败: ${e.message}")
              emptyList()
          }
    }

    // ============================================================
    // 私有：构建文�?Prompt
    // ============================================================

    private fun buildTextPrompt(query: String, nowMillis: Long): String {
        val cal   = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowStr = SimpleDateFormat("yyyy年MM月dd�?HH:mm", Locale.CHINA).format(Date(nowMillis))
        val year  = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1

        return """
你是一个手机相册搜索助手。当前时间是 $nowStr�?

用户查询�?$query"

解析为以�?JSON（只输出 JSON，不要任何解释或 Markdown 代码块）�?
{
  "mediaType": "ALL",
  "dateFrom": null,
  "dateTo": null,
  "filenameKeywords": [],
  "bucketNameKeywords": [],
  "visualQuery": null
}

字段说明�?
- mediaType: "ALL" / "IMAGE" / "VIDEO"
  "视频"/"录像" �?"VIDEO"�?照片"/"图片"/"截图" �?"IMAGE"；否�?�?"ALL"
- dateFrom/dateTo: "YYYY-MM-DD" �?null
  "今天" �?今日�?昨天" �?昨日�?本月" �?${year}-${month.toString().padStart(2,'0')}首尾�?
  "上个�? �?上月�?今年" �?${year}首尾�?去年" �?${year - 1}首尾；无时间 �?null
- filenameKeywords: 文件名关键词
  "截图" �?["Screenshot"]�?相机拍的" �?["IMG_","PXL_"]；其�?�?[]
- bucketNameKeywords: 相册目录名关键词
  "截图" �?["Screenshots","截图"]�?相机" �?["Camera","DCIM"]�?
  "微信" �?["微信","WeChat"]；其�?�?[]
- visualQuery: 视觉内容描述词（�?null 则需要用视觉 AI 扫描图片内容�?
  规则：查询描述的是图片中"看得�?的内容（物体/动物/人物/场景/活动�?�?提取简洁描�?
  示例�?猫咪" �?"猫咪"�?海边日落" �?"海边日落"�?朋友聚餐" �?"朋友聚餐"
  反例�?上个月的截图"（纯元数据）�?null�?视频" �?null
  注意：如同时有时�?视觉内容�?2024年的�?）→ �?dateFrom/dateTo AND visualQuery="�?

只输出纯 JSON�?
        """.trimIndent()
    }

    // ============================================================
    // 私有工具方法
    // ============================================================

    private fun parseJsonToCriteria(aiResponse: String, nowMillis: Long): SearchCriteria {
        return try {
            val obj: JsonObject = JsonParser.parseString(extractJson(aiResponse)).asJsonObject

            val mediaType = when (obj.get("mediaType")?.asString?.uppercase()) {
                "IMAGE" -> SearchMediaType.IMAGE
                "VIDEO" -> SearchMediaType.VIDEO
                else    -> SearchMediaType.ALL
            }
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dateFrom = obj.get("dateFrom")?.let { el ->
                if (el.isJsonNull) null
                else runCatching { sdf.parse(el.asString)?.time }.getOrNull()
            }
            val dateTo = obj.get("dateTo")?.let { el ->
                if (el.isJsonNull) null
                else runCatching { sdf.parse(el.asString)?.let { it.time + 86_400_000L - 1L } }.getOrNull()
            }
            val filenameKeywords   = obj.getAsJsonArray("filenameKeywords")
                ?.mapNotNull { it.asString.takeIf(String::isNotBlank) } ?: emptyList()
            val bucketNameKeywords = obj.getAsJsonArray("bucketNameKeywords")
                ?.mapNotNull { it.asString.takeIf(String::isNotBlank) } ?: emptyList()
            val visualQuery = obj.get("visualQuery")?.let { el ->
                if (el.isJsonNull) null else el.asString.takeIf(String::isNotBlank)
            }

            SearchCriteria(mediaType, dateFrom, dateTo, filenameKeywords, bucketNameKeywords, visualQuery)
        } catch (e: Exception) {
            android.util.Log.w("AiSearch", "JSON 解析失败，降级为空条�? ${e.message}")
            SearchCriteria()
        }
    }

    private fun parseIndexArray(text: String): List<Int> = try {
        val inner = Regex("\\[([^]]*)]").find(text.trim())?.groupValues?.get(1) ?: return emptyList()
        if (inner.isBlank()) emptyList()
        else inner.split(",").mapNotNull { it.trim().toIntOrNull() }
    } catch (e: Exception) { emptyList() }

    private fun extractJson(text: String): String {
        Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text.trim())?.let { return it.groupValues[1].trim() }
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        return if (s != -1 && e > s) text.substring(s, e + 1) else text.trim()
    }

    /** 将图�?URI 读取、等比缩�?MAX_VISUAL_DIM px，返�?JPEG Base64 �?null */
    private fun encodeImageToBase64(uri: android.net.Uri): String? = try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val original = BitmapFactory.decodeStream(inputStream).also { inputStream.close() }
        val scale = minOf(MAX_VISUAL_DIM.toFloat() / original.width,
                          MAX_VISUAL_DIM.toFloat() / original.height, 1f)
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
        else original
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== original) scaled.recycle()
        original.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        android.util.Log.w("AiVisualSearch", "图片编码失败 $uri: ${e.message}")
        null
    }
}


/**
 * AI 自然语言检索解�?Repository 实现（Data 层）
 *
 * 流程�?
 * 1. 检�?AI 是否已配置（未配置直接返�?Failure�?
 * 2. 将当前时�?+ 用户查询文本打包�?Prompt 发给 LLM
 * 3. LLM 返回 JSON 格式的结构化检索条�?
 * 4. 解析 JSON �?[SearchCriteria]
 *
 * ⚠️ 隐私安全�?
 * - 仅发送文字（当前时间 + 用户查询），绝对不上传任何图�?
 * - 所有实际的媒体过滤在设备本地完�?
 *
 * ⚠️ 异常处理�?
 * - 所有异常在此类内部捕获并转换为 [Result.failure]，不向外抛出
 */
