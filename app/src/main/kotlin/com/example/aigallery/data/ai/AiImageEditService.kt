package com.example.aigallery.data.ai

import com.example.aigallery.data.ai.dto.ImageSynthesisCreateResponse
import com.example.aigallery.data.ai.dto.ImageSynthesisQueryResponse
import com.example.aigallery.data.ai.dto.ImageSynthesisRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * 阿里云百炼「万相-通用图像编辑」（wanx2.1-imageedit）Retrofit 接口
 *
 * ⚠️ 这是 DashScope 原生接口，固定使用异步任务模式：
 * 1. [createTask] 提交编辑任务，拿到 task_id
 * 2. 轮询 [queryTask] 直到 task_status 为终态（SUCCEEDED/FAILED/CANCELED）
 *
 * Base URL 固定为 `https://dashscope.aliyuncs.com`（国内）或
 * `https://dashscope-intl.aliyuncs.com`（新加坡/国际版），与用户在设置页为
 * Chat 补全配置的 `compatible-mode` 端点是完全独立的两套路径，因此这里通过
 * @Url 直接传入固定域名拼接的完整地址，不复用用户配置的 baseUrl。
 */
interface AiImageEditService {

    @POST
    suspend fun createTask(
        @Url url: String,
        @Body request: ImageSynthesisRequest,
        @Header("X-DashScope-Async") asyncHeader: String = ASYNC_ENABLED
    ): ImageSynthesisCreateResponse

    @GET
    suspend fun queryTask(@Url url: String): ImageSynthesisQueryResponse

    companion object {
        const val ASYNC_ENABLED = "enable"
    }
}
