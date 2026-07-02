package com.example.aigallery.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.domain.repository.IAiSearchRepository
import com.example.aigallery.domain.repository.IMediaRepository
import com.example.aigallery.domain.repository.ITagRepository
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import kotlinx.coroutines.flow.first

/**
 * 后台 AI 打标 Worker（WorkManager）
 *
 * 通过 Hilt EntryPoint 获取依赖（无需 hilt-work 扩展库），
 * 运行在 IO 线程，不阻塞主界面。
 *
 * 流程：
 * 1. 从 MediaStore 读取所有照片（最多 [MAX_BATCH_TOTAL] 张）
 * 2. 过滤掉已打标照片（Room DB 查询）
 * 3. 分批（每批 [BATCH_SIZE] 张）调用 qwen-vl-max 生成标签
 * 4. 将结果持久化到 Room
 */
class PhotoTagWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    /** Hilt EntryPoint：从 Application 的 SingletonComponent 取出已注入的依赖 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun mediaRepository(): IMediaRepository
        fun tagRepository(): ITagRepository
        fun aiSearchRepository(): IAiSearchRepository
    }

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(
            applicationContext, Deps::class.java
        )
        val mediaRepo = ep.mediaRepository()
        val tagRepo   = ep.tagRepository()
        val aiRepo    = ep.aiSearchRepository()

        return try {
            // 只处理图片（排除视频和截图），取最近拍摄的
            val allImages = mediaRepo.getAllMedia().first()
                .filter { it.mediaType == MediaType.IMAGE && !it.isScreenshot }
                .take(MAX_BATCH_TOTAL)

            // 跳过已打标的照片（幂等：重复运行不会产生重复标签）
            val taggedUris = tagRepo.getAllTaggedUris().toSet()
            val untagged   = allImages.filter { it.uri.toString() !in taggedUris }

            if (untagged.isEmpty()) return Result.success()

            // 分批打标
            for (batch in untagged.chunked(BATCH_SIZE)) {
                val results = aiRepo.tagPhotoBatch(batch)
                for (r in results) {
                    tagRepo.saveTags(r.uri, r.tags)
                }
            }
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("PhotoTagWorker", "打标任务失败: ${e.message}")
            Result.failure()
        }
    }

    companion object {
        /** 单次任务最多处理的图片数量（控制 API 成本） */
        private const val MAX_BATCH_TOTAL = 120
        /** 每批图片数（≤3 以控制 Token 消耗） */
        private const val BATCH_SIZE = 3
        /** WorkManager 唯一任务名 */
        const val WORK_NAME = "ai_photo_tag"
    }
}
