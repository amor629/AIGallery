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
import com.example.aigallery.domain.model.PhotoTagTaxonomy
import com.example.aigallery.domain.model.SearchCriteria
import com.example.aigallery.domain.model.SearchMediaType
import com.example.aigallery.domain.repository.IAiSearchRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import com.example.aigallery.domain.model.WastePhoto
import com.example.aigallery.domain.model.PhotoTagResult

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
        // qwen-max / qwen-vl-max / qwen-vl-ocr 将于 2026-07-13 下线，已切换至替代模型
        // 视觉理解统一用 qwen3.7-plus（识图 / 文案 / 打标 / 搜索兜底 / 废片清理共用同一模型，
        // 保证各功能识别口径一致；速度通过批次大小 + 并发数调节，而非拆分模型版本）
        private const val MODEL_TEXT   = "qwen3.7-max"    // 文本意图解析
        private const val MODEL_VISUAL = "qwen3.7-plus"   // 视觉理解（识图/文案/打标/搜索兜底/废片清理）
        private const val MODEL_OCR    = "qwen3.5-ocr"    // 截图文字识别（OCR 专用模型，识别准确率优于通用视觉模型）
        private const val MAX_TOKENS_TEXT   = 256
        private const val MAX_TOKENS_VISUAL = 50        // 只需返回编号数组
        private const val MAX_TOKENS_OCR    = 512       // 截图文字通常较短，够用且控制成本
        private const val MAX_VISUAL_DIM    = 512       // 视觉搜索用较小缩略图
        private const val JPEG_QUALITY      = 75
        private const val MAX_WASTE_DIM     = 768       // 废片扫描用更高分辨率以提升人脸/模糊识别准确率
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


            // 附加问题文本（放在图片之后，部分多模态模型要求文本在最后）
            // bitmask 格式：让模型对每张图片独立打 0/1 分，避免"选号"时宁多勿少的偏差
            //
            // 注意：不再一刀切排除"截图"——如果用户搜的本来就是截图内容（如"短信"、"聊天记录"、"付款截图"），
            // 截图当然应该算命中；只有当截图/画面内容与查询无关时才不算命中，交给模型按内容本身判断。
            val n = contentParts.size
            contentParts.add(
                AiContentPart(
                    type = "text",
                    text = "以上 $n 张图片按编号 0~${n - 1} 排列。\n" +
                           "对每张图片独立判断：画面内容是否真实包含或展示了「$visualQuery」这一具体主体/内容。\n" +
                           "无论图片是真实拍摄的照片还是手机截图都一视同仁——只要画面内容本身确实体现了「$visualQuery」就算命中" +
                           "（例如查询「短信」时，真正的短信/聊天对话界面截图算命中；查询「电脑」时，画面中有电脑/电脑屏幕的照片算命中）。\n" +
                           "不算命中的情况：\n" +
                           "- 仅背景中的次要元素、画面模糊不清无法确认、或仅仅是间接联想到的内容\n" +
                           "- 图片中只是「包含文字/包含数字」，但内容并不是「$visualQuery」本身所指代的具体事物" +
                           "（例如查询「短信」时，食品包装、商品标签、代码界面、文档扫描件等只是「有文字」但不是短信对话的图片，不算命中）\n" +
                           "- 只是同类别下的其他子类型（例如查询「电脑」时，手机、平板、电视屏幕不算命中）\n" +
                           "按编号顺序输出 $n 个结果（命中=1，不命中=0），JSON 数组格式。\n" +
                           "示例（3张图）：[1,0,0]。只输出 JSON 数组，不要任何解释。"
                )
            )

            // 用 system 消息全局限制模型行为：按画面真实内容判断，宁漏勿误
            val systemMsg = AiMessage(
                role = "system",
                content = listOf(AiContentPart(
                    type = "text",
                    text = "你是严格的图片内容匹配器。只有当画面内容（照片或截图皆可）真实、明确地体现了用户指定的具体主体/内容时才输出 1；" +
                           "不要因为图片中「有文字」「有数字」或存在表面相似元素就判定命中，必须是该主体/内容本身。" +
                           "背景中的次要元素、模糊不清、仅间接相关的一律输出 0。宁可漏判，绝不误判。"
                ))
            )

            val response = aiChatService.chatCompletion(
                endpoint,
                AiChatRequest(model = MODEL_VISUAL, maxTokens = MAX_TOKENS_VISUAL,
                    messages = listOf(systemMsg, AiMessage(role = "user", content = contentParts)))
            )
            val content = response.choices?.firstOrNull()?.message?.content
                ?: return@withContext emptyList()

            // 解析 bitmask "[1,0,1]" → 命中下标 → 真实 MediaItem 下标
            parseBitmaskArray(content, n).mapNotNull { pos ->
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

    /**
     * 解析视觉搜索的 bitmask 响应 "[1,0,1]" → 返回值为 1 的位置列表。
     * 若返回的数组长度或取值不符合预期的 bitmask 格式，一律视为无命中（不做模糊兜底），
     * 避免把格式错乱的响应误判成命中，见下方实现注释。
     */
    private fun parseBitmaskArray(text: String, batchSize: Int): List<Int> {
        return try {
            val inner = Regex("""\[([^\]]*)\]""").find(text.trim())?.groupValues?.get(1)
                ?: return emptyList()
            if (inner.isBlank()) return emptyList()
            val values = inner.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (values.size == batchSize && values.all { it == 0 || it == 1 }) {
                // bitmask 格式：收集值为 1 的位置
                values.indices.filter { values[it] == 1 }
            } else {
                // 长度或取值不符合预期的 bitmask 格式，说明模型没有按格式回答（如漏答/多答/加了解释文字）。
                // 之前这里会"兜底"当作旧版下标列表处理，但这样会把一个畸形/截断的 bitmask
                // （比如本该是 [0,0,0] 却只返回了 [1,0]）误判成"命中下标 1 和 0"，
                // 从而把完全无关的图片（如人像自拍）错误地判定为搜索命中。
                // 按"宁可漏判，绝不误判"原则，格式不符时一律视为无命中，并记录原始响应便于排查。
                android.util.Log.w("AiVisualSearch", "响应格式不符合预期 bitmask（期望长度 $batchSize）: $text")
                emptyList()
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun extractJson(text: String): String {
        Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text.trim())?.let { return it.groupValues[1].trim() }
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        return if (s != -1 && e > s) text.substring(s, e + 1) else text.trim()
    }

    /** 将图�?URI 读取、等比缩�?MAX_VISUAL_DIM px，返�?JPEG Base64 �?null */
    private fun encodeImageToBase64(uri: android.net.Uri, maxDim: Int = MAX_VISUAL_DIM): String? = try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val original = BitmapFactory.decodeStream(inputStream).also { inputStream.close() }
        val scale = minOf(maxDim.toFloat() / original.width,
                          maxDim.toFloat() / original.height, 1f)
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

    // ============================================================
    // analyzeWasteBatch：废片批量识别（模糊 / 闭眼 / 重复 / 截图）
    // ============================================================

    override suspend fun analyzeWasteBatch(mediaItems: List<MediaItem>): List<WastePhoto> =
        withContext(Dispatchers.IO) {
        try {
            val endpoint = aiApiClient.requireBaseUrl().trimEnd('/') + "/chat/completions"
            val contentParts   = mutableListOf<AiContentPart>()
            val encodedIndices = mutableListOf<Int>()

            for (i in mediaItems.indices) {
                val b64 = encodeImageToBase64(mediaItems[i].uri, MAX_WASTE_DIM) ?: continue
                contentParts.add(AiContentPart(type = "image_url", imageUrl = AiImageUrl("data:image/jpeg;base64,$b64")))
                encodedIndices.add(i)
            }
            if (contentParts.isEmpty()) return@withContext emptyList()

            val n = contentParts.size
            contentParts.add(AiContentPart(
                type = "text",
                text = "以下 $n 张图片按编号 0~${n-1} 排列，逐张判断是否属于废片。\n" +
                       "废片标准：\n" +
                       "  1. 模糊——整体画面模糊、抖动、焦点失准（非刻意艺术效果）\n" +
                       "  2. 闭眼——画面中有人物，且存在明显闭眼的情况（双眼完全或大部分闭合）\n" +
                       "  3. 重复——本批次中有两张或以上内容极其相似（主体、构图几乎一致）\n" +
                       "  4. 截图——手机屏幕截图，而非真实场景拍摄\n" +
                       "只标记明确的废片，不确定的一律不标记。\n" +
                       "返回 JSON 数组：[{\"index\":0,\"reason\":\"模糊\"}]，无废片返回 []。只输出 JSON。"
            ))
            val systemMsg = AiMessage(role = "system", content = listOf(AiContentPart(type = "text",
                text = "你是专业废片识别助手，判断严格，只标记明确废片。")))

            val response = aiChatService.chatCompletion(endpoint,
                AiChatRequest(model = MODEL_VISUAL, maxTokens = 128,
                    messages = listOf(systemMsg, AiMessage(role = "user", content = contentParts))))
            val content = response.choices?.firstOrNull()?.message?.content
                ?: return@withContext emptyList()

            parseWasteArray(content, encodedIndices, mediaItems)
        } catch (e: AiApiClient.AiNotConfiguredException) { emptyList() }
          catch (e: Exception) {
              android.util.Log.w("AiWasteScan", "废片识别失败: ${e.message}")
              emptyList()
          }
    }

    private fun parseWasteArray(text: String, encodedIndices: List<Int>, mediaItems: List<MediaItem>): List<WastePhoto> {
        return try {
            val arrayStr = Regex("\\[[\\s\\S]*?\\]").find(text.trim())?.value ?: return emptyList()
            val arr = JsonParser.parseString(arrayStr).asJsonArray
            arr.mapNotNull { elem ->
                val obj    = runCatching { elem.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val idx    = obj.get("index")?.asInt ?: return@mapNotNull null
                val reason = obj.get("reason")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val mi = if (idx in encodedIndices.indices) encodedIndices[idx] else return@mapNotNull null
                if (mi in mediaItems.indices) WastePhoto(mediaItems[mi], reason) else null
            }
        } catch (e: Exception) { emptyList() }
    }

    // ============================================================
    // tagPhotoBatch：场景标签批量标注
    // ============================================================

    override suspend fun tagPhotoBatch(mediaItems: List<MediaItem>): List<PhotoTagResult> =
        withContext(Dispatchers.IO) {
        try {
            val endpoint = aiApiClient.requireBaseUrl().trimEnd('/') + "/chat/completions"
            val contentParts   = mutableListOf<AiContentPart>()
            val encodedIndices = mutableListOf<Int>()

            for (i in mediaItems.indices) {
                val b64 = encodeImageToBase64(mediaItems[i].uri, MAX_WASTE_DIM) ?: continue
                contentParts.add(AiContentPart(type = "image_url", imageUrl = AiImageUrl("data:image/jpeg;base64,$b64")))
                encodedIndices.add(i)
            }
            if (contentParts.isEmpty()) return@withContext emptyList()

            // 若批次里有截图，先用 OCR 模型提取文字内容，帮助后续分类模型更准确判断
            // 截图具体属于哪一类（短信/聊天记录/支付订单等），而不是仅凭视觉猜测。
            val ocrTexts: Map<Int, String> = extractScreenshotTexts(mediaItems, encodedIndices)

            val n = contentParts.size
            val ocrHint = if (ocrTexts.isNotEmpty()) {
                "以下是部分截图通过 OCR 识别出的文字内容，可作为判断该图片具体类别的重要依据：\n" +
                    ocrTexts.entries.joinToString("\n") { (idx, text) -> "图片$idx 的文字内容：${text.take(200)}" } + "\n\n"
            } else ""

            contentParts.add(AiContentPart(
                type = "text",
                text = ocrHint +
                       "以下 $n 张图片按编号 0~${n - 1} 排列。\n" +
                       "为每张图片选出 1 个（最多 2 个，仅当画面确实同时体现两个同等重要的主题时才用 2 个）最能概括整体内容的中文分类标签。\n" +
                       "标签必须从下面的固定分类列表中选择，不要自造新词，不要拆分出过细的子类型：\n" +
                       PhotoTagTaxonomy.CATEGORIES.joinToString("、") + "\n" +
                       "重要规则：\n" +
                       "1. 只选整体场景/主体所属的大类，不要标注细节特征" +
                       "（例如人物照片一律标「人像/自拍」，不要单独标「眼镜」「短发」「宿舍」「戴帽子」这类细节或环境）\n" +
                       "2. 同一张照片不要因为背景元素不同就多加标签，只选画面的核心主体\n" +
                       "3. 截图类：先看 OCR 文字内容判断具体属于短信/聊天记录/支付订单/二维码/社交媒体/游戏画面/影视截图中的哪一类；" +
                       "无法判断具体类型的截图统一标「其他截图」，不要标「社交媒体」等模糊猜测\n" +
                       "4. 不属于以上任何类别的，统一标「其他」\n" +
                       "返回 JSON 数组：[{\"index\":0,\"tags\":[\"美食\"]},{\"index\":1,\"tags\":[\"电子设备\"]}]\n" +
                       "只输出 JSON，不要解释。"
            ))
            val systemMsg = AiMessage(role = "system", content = listOf(AiContentPart(type = "text",
                text = "你是图片分类助手，任务是把每张照片归入少量粗粒度类别（用于生成相册），而不是做精细化标注。" +
                       "同一类照片应该被打上相同的标签，避免同一张照片被拆分进多个高度相似的分类。")))

            val response = aiChatService.chatCompletion(endpoint,
                AiChatRequest(model = MODEL_VISUAL, maxTokens = 384,
                    messages = listOf(systemMsg, AiMessage(role = "user", content = contentParts))))
            val content = response.choices?.firstOrNull()?.message?.content
                ?: return@withContext emptyList()

            parseTagArray(content, encodedIndices, mediaItems, ocrTexts)
        } catch (e: AiApiClient.AiNotConfiguredException) { emptyList() }
          catch (e: Exception) {
              android.util.Log.w("AiTagging", "打标失败: ${e.message}")
              emptyList()
          }
    }

    /**
     * 对批次中属于截图的图片调用 OCR 模型（[MODEL_OCR]）提取文字，返回 "contentParts 内下标 -> OCR 文本" 映射。
     * 非截图图片跳过；OCR 失败的图片直接忽略（不影响主流程分类）。
     */
    private suspend fun extractScreenshotTexts(
        mediaItems: List<MediaItem>,
        encodedIndices: List<Int>
    ): Map<Int, String> = coroutineScope {
        val endpoint = try {
            aiApiClient.requireBaseUrl().trimEnd('/') + "/chat/completions"
        } catch (e: AiApiClient.AiNotConfiguredException) {
            return@coroutineScope emptyMap()
        }
        // 批次内的截图数量很小（≤ BATCH_SIZE），直接并发请求即可，无需额外信号量控制
        encodedIndices.withIndex()
            .filter { (_, mediaIdx) -> mediaItems[mediaIdx].isScreenshot }
            .map { (partIdx, mediaIdx) ->
                async { partIdx to recognizeText(endpoint, mediaItems[mediaIdx].uri) }
            }
            .awaitAll()
            .mapNotNull { (partIdx, text) -> text?.takeIf { it.isNotBlank() }?.let { partIdx to it } }
            .toMap()
    }

    /**
     * 标记专用 OCR 模型 [MODEL_OCR] 在本次进程运行期间是否可用。
     *
     * 背景：如果该模型在百炼控制台未开通/不可用，每张截图都会先请求一次 [MODEL_OCR]
     * （必然失败并耗费一次完整的网络往返 + 超时等待），再降级到 [MODEL_VISUAL] 重新请求，
     * 导致所有截图的打标耗时几乎翻倍。首次确认调用失败后关闭开关，后续截图直接跳过
     * 该模型，避免重复浪费在一个已知不可用的模型上；用 @Volatile 是因为多个并发批次
     * （打标 Worker 用 Semaphore 允许多个批次同时在途）可能同时读写这个标志。
     */
    @Volatile private var ocrModelAvailable = true

    /**
     * 识别单张图片中的文字内容：优先用专用 OCR 模型 [MODEL_OCR]（识别更准），
     * 若该模型调用失败（例如尚未在百炼控制台"开通"该模型、当前地域/接入点不支持等），
     * 自动降级用通用视觉模型 [MODEL_VISUAL]（用户已在用、必定可用）做文字提取兜底，
     * 保证 OCR 辅助分类功能不会因为一个模型不可用就完全失效。
     */
    private suspend fun recognizeText(endpoint: String, uri: android.net.Uri): String? {
        val b64 = encodeImageToBase64(uri, MAX_WASTE_DIM) ?: return null
        if (ocrModelAvailable) {
            recognizeTextWithModel(endpoint, b64, MODEL_OCR)?.let { return it }
            ocrModelAvailable = false
            android.util.Log.w("AiOcr", "$MODEL_OCR 不可用，本次运行期间后续截图直接使用 $MODEL_VISUAL 提取文字")
        }
        return recognizeTextWithModel(endpoint, b64, MODEL_VISUAL)
    }

    private suspend fun recognizeTextWithModel(endpoint: String, imageBase64: String, model: String): String? = try {
        android.util.Log.d("AiOcr", "开始文字识别 (model=$model)")
        val response = aiChatService.chatCompletion(
            endpoint,
            AiChatRequest(
                model = model,
                maxTokens = MAX_TOKENS_OCR,
                messages = listOf(AiMessage(
                    role = "user",
                    content = listOf(
                        AiContentPart(type = "image_url", imageUrl = AiImageUrl("data:image/jpeg;base64,$imageBase64")),
                        AiContentPart(type = "text", text = "请输出图片中的全部文字内容，不要任何解释或格式化。")
                    )
                ))
            )
        )
        val text = response.choices?.firstOrNull()?.message?.content
        android.util.Log.d("AiOcr", "文字识别成功 (model=$model)，长度=${text?.length ?: 0}: ${text?.take(50)}")
        text
    } catch (e: HttpException) {
        // 常见原因：该模型未在百炼控制台"开通"（403/404）、Key 无该模型权限、或该模型名称在当前地域/接入点不存在
        val errorBody = try { e.response()?.errorBody()?.string() } catch (ignored: Exception) { null }
        android.util.Log.w("AiOcr", "文字识别失败 (model=$model) HTTP ${e.code()}: ${errorBody ?: e.message()}")
        null
    } catch (e: Exception) {
        android.util.Log.w("AiOcr", "文字识别失败 (model=$model): ${e.message}")
        null
    }

    private fun parseTagArray(
        text: String,
        encodedIndices: List<Int>,
        mediaItems: List<MediaItem>,
        ocrTexts: Map<Int, String>
    ): List<PhotoTagResult> {
        return try {
            val s = text.indexOf("["); val e = text.lastIndexOf("]")
            val arrayStr = if (s != -1 && e > s) text.substring(s, e + 1) else return emptyList()
            val arr = JsonParser.parseString(arrayStr).asJsonArray
            arr.mapNotNull { elem ->
                val obj  = runCatching { elem.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val idx  = obj.get("index")?.asInt ?: return@mapNotNull null
                val tags = obj.getAsJsonArray("tags")?.mapNotNull { it.asString.takeIf { s -> s.isNotBlank() } }
                    ?: return@mapNotNull null
                val mi = if (idx in encodedIndices.indices) encodedIndices[idx] else return@mapNotNull null
                if (mi in mediaItems.indices && tags.isNotEmpty())
                    PhotoTagResult(mediaItems[mi].uri.toString(), tags, ocrTexts[idx])
                else null
            }
        } catch (e: Exception) { emptyList() }
    }
}
