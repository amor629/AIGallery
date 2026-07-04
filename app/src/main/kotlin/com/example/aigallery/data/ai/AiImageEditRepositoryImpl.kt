package com.example.aigallery.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.aigallery.ai.AiApiClient
import com.example.aigallery.data.ai.dto.ImageSynthesisInput
import com.example.aigallery.data.ai.dto.ImageSynthesisParameters
import com.example.aigallery.data.ai.dto.ImageSynthesisRequest
import com.example.aigallery.data.ai.dto.ImageSynthesisTaskOutput
import com.example.aigallery.domain.model.AiEditType
import com.example.aigallery.domain.model.AiImageEditResult
import com.example.aigallery.domain.repository.IAiImageEditRepository
import com.example.aigallery.domain.repository.IMediaSaveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 图片编辑 Repository 实现（老照片修复 / AI 照片美化）
 *
 * 均通过阿里云百炼「万相-通用图像编辑」（wanx2.1-imageedit）大模型完成，不含任何本地
 * 离线算法兜底。
 *
 * ⚠️ 该接口是 DashScope 原生接口（非 OpenAI 兼容模式），调用方式与 Chat/视觉理解
 * （走用户在设置页配置的 `baseUrl` + `/chat/completions`）完全不同：
 * 1. 固定使用官方域名 `https://dashscope.aliyuncs.com`，与用户配置的 baseUrl 无关，
 *    仅复用用户在设置页保存的百炼 API Key 做鉴权；
 * 2. 只支持异步任务模式：先 [AiImageEditService.createTask] 提交任务拿到 task_id，
 *    再轮询 [AiImageEditService.queryTask] 直到任务进入终态；
 * 3. 图片必须以 `data:{mime};base64,{data}` 形式塞进 JSON body（而不是本地文件路径
 *    或 multipart 上传），因为服务端无法访问手机本地的 content:// URI。
 *
 * ⚠️ 画质保护：为避免 AI 处理后分辨率明显低于原图，这里：
 * 1. 上传时使用较高的分辨率上限（[MAX_UPLOAD_DIM]）和较高的 JPEG 质量；
 * 2. 拿到模型返回结果后，若其分辨率低于原图，会再等比放大回原图尺寸，
 *    避免保存后出现肉眼可见的"画质下降"。
 */
@Singleton
class AiImageEditRepositoryImpl @Inject constructor(
    private val aiApiClient: AiApiClient,
    private val aiImageEditService: AiImageEditService,
    private val mediaSaveRepository: IMediaSaveRepository
) : IAiImageEditRepository {

    companion object {
        /** 阿里云百炼「万相-通用图像编辑」模型标识 */
        private const val MODEL_NAME = "wanx2.1-imageedit"

        /** DashScope 原生接口固定域名（中国内地站点） */
        private const val DASHSCOPE_HOST = "https://dashscope.aliyuncs.com"
        private const val CREATE_TASK_URL = "$DASHSCOPE_HOST/api/v1/services/aigc/image2image/image-synthesis"
        private const val QUERY_TASK_URL_PREFIX = "$DASHSCOPE_HOST/api/v1/tasks/"

        private const val FUNCTION_EDIT = "description_edit"

        /** wanx 要求输入图像宽高均在 [512, 4096] 像素区间内；尽量取高值以保留画质 */
        private const val MAX_UPLOAD_DIM = 2048
        private const val MIN_UPLOAD_DIM = 512
        private const val UPLOAD_JPEG_QUALITY = 97

        /** 任务轮询间隔与最大轮询次数（间隔 2s，最多轮询 40 次 ≈ 80 秒超时） */
        private const val POLL_INTERVAL_MS = 2000L
        private const val MAX_POLL_ATTEMPTS = 40

        private const val TASK_SUCCEEDED = "SUCCEEDED"
    }

    override suspend fun processImage(uri: Uri, type: AiEditType): AiImageEditResult =
        withContext(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            try {
                try {
                    aiApiClient.requireBaseUrl()
                } catch (e: AiApiClient.AiNotConfiguredException) {
                    return@withContext AiImageEditResult.Error("请先在设置页填写百炼 API 地址和 Key")
                }

                // 原图真实分辨率（用于处理完成后还原尺寸，避免画质肉眼可见地下降）
                val originalSize = mediaSaveRepository.readImageSize(uri)

                bitmap = mediaSaveRepository.loadBitmap(uri, MAX_UPLOAD_DIM)
                    ?: return@withContext AiImageEditResult.Error("无法读取图片")
                bitmap = ensureMinDimension(bitmap, MIN_UPLOAD_DIM)

                val baseImageDataUri = bitmapToDataUri(bitmap, Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY)

                val request = ImageSynthesisRequest(
                    model = MODEL_NAME,
                    input = ImageSynthesisInput(
                        function = FUNCTION_EDIT,
                        prompt = promptFor(type),
                        baseImageUrl = baseImageDataUri
                    ),
                    parameters = ImageSynthesisParameters(
                        n = 1,
                        watermark = false,
                        strength = strengthFor(type)
                    )
                )

                val createResp = aiImageEditService.createTask(url = CREATE_TASK_URL, request = request)
                val taskId = createResp.output?.taskId
                    ?: return@withContext AiImageEditResult.Error(
                        createResp.message ?: "AI 任务提交失败，请重试"
                    )

                val finalOutput = pollTask(taskId)
                    ?: return@withContext AiImageEditResult.Error("AI 处理超时，请稍后重试")

                if (finalOutput.taskStatus != TASK_SUCCEEDED) {
                    return@withContext AiImageEditResult.Error(finalOutput.message ?: "AI 处理失败，请重试")
                }

                val resultUrl = finalOutput.results?.firstOrNull { !it.url.isNullOrBlank() }?.url
                    ?: return@withContext AiImageEditResult.Error(
                        finalOutput.results?.firstOrNull()?.message ?: "AI 未返回有效图片，请重试"
                    )
                val resultBitmap = URL(resultUrl).openStream().use { BitmapFactory.decodeStream(it) }
                    ?: return@withContext AiImageEditResult.Error("下载结果图片失败，请重试")

                // 若模型返回的分辨率低于原图，等比放大回原图尺寸，避免保存后画质明显下降
                val finalBitmap = restoreOriginalResolution(resultBitmap, originalSize)

                AiImageEditResult.ReadyToSave(finalBitmap, uri, type)
            } catch (e: HttpException) {
                when (e.code()) {
                    401  -> AiImageEditResult.Error("API Key 无效或已过期，请在设置页重新配置")
                    429  -> AiImageEditResult.Error("请求过于频繁，请稍后再试（限流）")
                    400  -> AiImageEditResult.Error("请求参数有误，请重试（可能是图片尺寸不符合要求）")
                    else -> AiImageEditResult.Error("服务器错误 (HTTP ${e.code()})，请稍后重试")
                }
            } catch (e: SocketTimeoutException) {
                AiImageEditResult.Error("请求超时，图片编辑通常耗时较长，请稍后重试")
            } catch (e: IOException) {
                AiImageEditResult.Error("网络连接失败，请检查网络后重试")
            } catch (e: Exception) {
                AiImageEditResult.Error("处理出错，请重试")
            } finally {
                bitmap?.recycle()
            }
        }

    // ----------------------------------------------------------------
    // 任务轮询：DashScope 图像编辑为纯异步接口，必须轮询 task_status 直到终态
    // ----------------------------------------------------------------

    private suspend fun pollTask(taskId: String): ImageSynthesisTaskOutput? {
        repeat(MAX_POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val resp = aiImageEditService.queryTask(QUERY_TASK_URL_PREFIX + taskId)
            val output = resp.output ?: return@repeat
            when (output.taskStatus) {
                TASK_SUCCEEDED, "FAILED", "CANCELED", "UNKNOWN" -> return output
                else -> {} // PENDING / RUNNING，继续轮询
            }
        }
        return null
    }

    // ----------------------------------------------------------------
    // function / 提示词 / 改动幅度映射
    // ----------------------------------------------------------------

    /** 图像修改幅度：越大改动越大。老照片修复偏保守，AI 美化需要更明显的效果 */
    private fun strengthFor(type: AiEditType): Float = when (type) {
        AiEditType.BEAUTIFY -> 0.65f
        else -> 0.4f
    }

    private fun promptFor(type: AiEditType): String = when (type) {
        AiEditType.RESTORE ->
            "这是一张老照片，请修复它：去除划痕、噪点、褪色和泛黄，提升清晰度和细节，还原真实自然的色彩与光影。" +
            "请保持画面清晰锐利，不要模糊、不要降低分辨率和细节质感；不要改变照片的整体内容、构图和人物身份特征；" +
            "如果原图中有水印、文字、日期戳或 logo，请原样保留，不要移除或改动。"
        AiEditType.BEAUTIFY ->
            "请对这张人像照片进行高强度、效果显著的美颜精修，大幅提升颜值：" +
            "优化脸型比例使其更精致小巧、放大并提亮双眼、增加鼻梁高挺立体感、优化唇形和下颌线条、" +
            "均匀肤色并深度磨皮祛痘祛斑（同时保留清晰自然的皮肤质感，不要磨得假或糊）、改善发型光泽感；" +
            "整体效果要让相貌明显变得更英俊帅气或更漂亮迷人，达到网红级精修水准，" +
            "即使原本相貌普通也要让人物看起来出众好看。如果照片中没有人像，则对风景或场景进行画质增强、" +
            "色彩饱和度和光影层次优化，让画面更通透精美。" +
            "严格要求：输出图片必须清晰锐利、细节丰富，不允许出现模糊、噪点增多、分辨率降低等画质下降的情况；" +
            "不要改变图片的整体构图、背景内容和人物数量；如果原图中带有水印、文字、日期戳或 logo，" +
            "必须原样保留在输出图片中，绝对不能移除、遮挡或改动这些标识。"
        else -> "请优化这张图片，保持画质清晰，不要移除水印。"
    }

    // ----------------------------------------------------------------
    // 图片预处理 / 后处理辅助
    // ----------------------------------------------------------------

    /** wanx 要求图像宽高均不小于 512px，过小则等比放大 */
    private fun ensureMinDimension(bitmap: Bitmap, minDim: Int): Bitmap {
        val minSide = minOf(bitmap.width, bitmap.height)
        if (minSide >= minDim) return bitmap
        val scale = minDim.toFloat() / minSide
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(minDim)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(minDim)
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * 模型输出分辨率往往低于原图（内部有渲染尺寸上限）。
     * 若结果图明显小于原图，等比放大回原图尺寸，避免保存后与原图相比画质肉眼可见地下降。
     */
    private fun restoreOriginalResolution(result: Bitmap, originalSize: Pair<Int, Int>?): Bitmap {
        val (origW, origH) = originalSize ?: return result
        if (origW <= 0 || origH <= 0) return result
        if (result.width >= origW && result.height >= origH) return result
        val scaled = Bitmap.createScaledBitmap(result, origW, origH, true)
        if (scaled !== result) result.recycle()
        return scaled
    }

    private fun bitmapToDataUri(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String {
        val bos = ByteArrayOutputStream()
        bitmap.compress(format, quality, bos)
        val base64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        return "data:$mime;base64,$base64"
    }
}
