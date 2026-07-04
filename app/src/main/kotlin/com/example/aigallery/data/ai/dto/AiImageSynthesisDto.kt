package com.example.aigallery.data.ai.dto

import com.google.gson.annotations.SerializedName

/**
 * 阿里云百炼「万相-通用图像编辑」（wanx2.1-imageedit）任务提交请求体
 *
 * 参考文档：https://help.aliyun.com/zh/model-studio/wanx-image-edit-api-reference
 * 该接口为原生 DashScope 接口（非 OpenAI 兼容模式），固定走
 * `https://dashscope.aliyuncs.com/api/v1/services/aigc/image2image/image-synthesis`，
 * 与 Chat 补全使用的 `compatible-mode` 端点是两套完全独立的路径。
 */
data class ImageSynthesisRequest(
    @SerializedName("model") val model: String,
    @SerializedName("input") val input: ImageSynthesisInput,
    @SerializedName("parameters") val parameters: ImageSynthesisParameters
)

data class ImageSynthesisInput(
    /** 图像编辑功能：description_edit（指令编辑，无需蒙版）/ description_edit_with_mask（局部重绘，需蒙版）等 */
    @SerializedName("function") val function: String,
    @SerializedName("prompt") val prompt: String,
    /** 公网 URL 或 data:{MIME};base64,{data} 格式的 Base64 编码图像 */
    @SerializedName("base_image_url") val baseImageUrl: String,
    /** 仅 description_edit_with_mask 需要：白色=待编辑区域，黑色=保持不变 */
    @SerializedName("mask_image_url") val maskImageUrl: String? = null
)

data class ImageSynthesisParameters(
    @SerializedName("n") val n: Int = 1,
    @SerializedName("watermark") val watermark: Boolean = false,
    /** 仅 description_edit / stylization_all 支持：0~1，越大改动幅度越大 */
    @SerializedName("strength") val strength: Float? = null
)

/** 创建任务的响应（成功时含 output.task_id；失败时仅有顶层 code/message，没有 output） */
data class ImageSynthesisCreateResponse(
    @SerializedName("request_id") val requestId: String?,
    @SerializedName("code")       val code: String?,
    @SerializedName("message")    val message: String?,
    @SerializedName("output")     val output: ImageSynthesisTaskOutput?
)

/** 查询任务结果的响应 */
data class ImageSynthesisQueryResponse(
    @SerializedName("request_id") val requestId: String?,
    @SerializedName("output")     val output: ImageSynthesisTaskOutput?
)

data class ImageSynthesisTaskOutput(
    @SerializedName("task_id")     val taskId: String?,
    /** PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED / UNKNOWN */
    @SerializedName("task_status") val taskStatus: String?,
    @SerializedName("results")     val results: List<ImageSynthesisResultItem>?,
    /** 任务失败（FAILED）时返回具体错误码和信息 */
    @SerializedName("code")        val code: String?,
    @SerializedName("message")     val message: String?
)

data class ImageSynthesisResultItem(
    @SerializedName("url")     val url: String?,
    @SerializedName("code")    val code: String?,
    @SerializedName("message") val message: String?
)
