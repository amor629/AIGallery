package com.example.aigallery.domain.repository

import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.PhotoTagResult
import com.example.aigallery.domain.model.SearchCriteria
import com.example.aigallery.domain.model.WastePhoto

/**
 * AI 检索与视觉分析接口（Domain 层抽象）
 */
interface IAiSearchRepository {

    /** 将自然语言查询解析为结构化检索条件 */
    suspend fun parseQuery(query: String, nowMillis: Long): Result<SearchCriteria>

    /**
     * 对一批图片进行视觉内容匹配
     * @param mediaItems  待检测图片列表（建议 ≤3 张）
     * @param visualQuery 视觉内容描述词（如"猫咪"）
     * @return 命中图片在 mediaItems 中的下标列表；失败返回空列表
     */
    suspend fun visualSearchBatch(mediaItems: List<MediaItem>, visualQuery: String): List<Int>

    /**
     * 对一批图片进行废片分析（模糊 / 闭眼 / 重复 / 截图）
     * @param mediaItems 待分析图片列表（建议 ≤3 张）
     * @return 废片列表，含图片元数据及废片原因；失败返回空列表
     */
    suspend fun analyzeWasteBatch(mediaItems: List<MediaItem>): List<WastePhoto>

    /**
     * 对一批图片进行场景标签标注
     * @param mediaItems 待打标图片列表（建议 ≤3 张）
     * @return 每张图片的 URI + 标签列表；失败返回空列表
     */
    suspend fun tagPhotoBatch(mediaItems: List<MediaItem>): List<PhotoTagResult>
}
