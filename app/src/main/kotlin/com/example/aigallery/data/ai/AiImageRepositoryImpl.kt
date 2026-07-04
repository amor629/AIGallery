package com.example.aigallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.aigallery.ai.AiApiClient
import com.example.aigallery.data.ai.dto.AiChatRequest
import com.example.aigallery.data.ai.dto.AiContentPart
import com.example.aigallery.data.ai.dto.AiImageUrl
import com.example.aigallery.data.ai.dto.AiMessage
import com.example.aigallery.domain.model.AiAnalysisResult
import com.example.aigallery.domain.repository.IAiImageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 图片分析 Repository 实现（Data 层）
 *
 * 功能流程：
 * 1. 检查 AI 是否已配置（未配置则立即返回 Error，不发出任何网络请求）
 * 2. 读取图片 URI → 降采样（最长边 ≤ 1024px）→ 压缩为 JPEG → Base64 编码
 * 3. 构建 DashScope 兼容请求体，调用 [AiChatService.chatCompletion]
 * 4. 解析响应，将所有异常转换为对用户友好的 [AiAnalysisResult.Error]（绝不崩溃）
 *
 * ⚠️ 安全约束：
 * - API Key 由 [AiApiClient] 的拦截器注入，此类不直接接触 Key
 * - 所有异常均在内部捕获，不向上传播
 */
@Singleton
class AiImageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiApiClient: AiApiClient,
    private val aiChatService: AiChatService
) : IAiImageRepository {

    companion object {
        /** qwen3.7-plus：阿里云百炼平台视觉理解模型（原 qwen-vl-max 将于 2026-07-13 下线，已切换），
         *  与打标/搜索兜底/废片清理用同一模型，保证识图/文案生成的识别口径一致 */
        private const val MODEL_NAME = "qwen3.7-plus"

        /**
         * 压缩后的图片最长边上限（像素）
         * 1024px 在保证 AI 分析质量的前提下最大限度减少 Base64 体积和 API Token 消耗
         */
        private const val MAX_IMAGE_DIM = 1024

        /** JPEG 压缩质量（0-100），85 在质量和体积之间取得良好平衡 */
        private const val JPEG_QUALITY = 85
    }

    // ----------------------------------------------------------------
    // IAiImageRepository 实现
    // ----------------------------------------------------------------

    override suspend fun analyzeImage(uri: Uri, prompt: String): AiAnalysisResult {
        return withContext(Dispatchers.IO) {
            try {
                // ① 检查 AI 是否已配置（未配置立即返回，不发出网络请求）
                val baseUrl = try {
                    aiApiClient.requireBaseUrl()
                } catch (e: AiApiClient.AiNotConfiguredException) {
                    return@withContext AiAnalysisResult.Error("请先在设置页填写 API 地址和 Key")
                }

                // ② 拼接完整的 chat/completions 端点 URL
                val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

                // ③ 读取图片并编码为 Base64（降采样后压缩，控制体积）
                val base64Image = prepareImageBase64(uri)
                    ?: return@withContext AiAnalysisResult.Error("无法读取图片，请检查文件是否存在")

                // ④ 构建符合 DashScope 多模态格式的请求体
                val request = AiChatRequest(
                    model = MODEL_NAME,
                    messages = listOf(
                        AiMessage(
                            role = "user",
                            content = listOf(
                                // 图片片段（data URI 格式）
                                AiContentPart(
                                    type = "image_url",
                                    imageUrl = AiImageUrl("data:image/jpeg;base64,$base64Image")
                                ),
                                // 文字提示
                                AiContentPart(type = "text", text = prompt)
                            )
                        )
                    )
                )

                // ⑤ 发起网络请求
                val response = aiChatService.chatCompletion(endpoint, request)

                // ⑥ 解析响应
                val content = response.choices?.firstOrNull()?.message?.content
                when {
                    !content.isNullOrBlank() -> AiAnalysisResult.Success(content)
                    response.error != null   -> AiAnalysisResult.Error(response.error.message)
                    else                     -> AiAnalysisResult.Error("AI 返回了空响应，请重试")
                }

            } catch (e: HttpException) {
                // HTTP 层错误（4xx / 5xx）
                when (e.code()) {
                    401  -> AiAnalysisResult.Error("API Key 无效或已过期，请在设置页重新配置")
                    429  -> AiAnalysisResult.Error("请求过于频繁，请稍后再试（限流）")
                    400  -> AiAnalysisResult.Error("请求格式错误，请检查模型名称是否正确")
                    else -> AiAnalysisResult.Error("服务器错误 (HTTP ${e.code()})，请稍后重试")
                }
            } catch (e: SocketTimeoutException) {
                AiAnalysisResult.Error("请求超时，请检查网络连接后重试")
            } catch (e: IOException) {
                AiAnalysisResult.Error("网络连接失败，请检查网络后重试")
            } catch (e: Exception) {
                AiAnalysisResult.Error("未知错误，请重试")
            }
        }
    }

    // ----------------------------------------------------------------
    // 图片预处理：降采样 + 压缩 + Base64 编码
    // ----------------------------------------------------------------

    /**
     * 将 content:// URI 的图片准备为 Base64 字符串
     *
     * 处理步骤：
     * 1. 先用 inJustDecodeBounds 探测图片尺寸（不加载像素，不占大量内存）
     * 2. 计算 inSampleSize，让最长边 ≤ [MAX_IMAGE_DIM]
     * 3. 以降采样系数实际加载 Bitmap
     * 4. 压缩为 JPEG（质量 [JPEG_QUALITY]）
     * 5. Base64 编码（NO_WRAP 格式，无换行符）
     *
     * @return Base64 字符串，读取失败时返回 null
     */
    private fun prepareImageBase64(uri: Uri): String? {
        return try {
            // 第一步：探测尺寸（不加载像素）
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            // 第二步：计算降采样系数（2 的整数次幂，满足 BitmapFactory 规范）
            var sampleSize = 1
            val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            while (maxDim / sampleSize > MAX_IMAGE_DIM) sampleSize *= 2

            // 第三步：以降采样系数加载 Bitmap
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return null

            // 第四步：压缩为 JPEG 字节
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            bitmap.recycle()  // 立即释放 Bitmap 内存，避免 OOM

            // 第五步：Base64 编码（NO_WRAP = 不插入换行符，适合 JSON 传输）
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        } catch (e: Exception) {
            null
        }
    }
}
