package com.example.aigallery.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.aigallery.R
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.domain.repository.IAiSearchRepository
import com.example.aigallery.domain.repository.IMediaRepository
import com.example.aigallery.domain.repository.ITagRepository
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 后台 AI 打标 Worker（WorkManager）
 *
 * 通过 Hilt EntryPoint 获取依赖（无需 hilt-work 扩展库），
 * 运行在 IO 线程，不阻塞主界面。
 *
 * 流程：
 * 1. 从 MediaStore 读取所有照片（最多 [MAX_BATCH_TOTAL] 张）
 * 2. 过滤掉已打标照片（Room DB 查询）
 * 3. 分批（每批 [BATCH_SIZE] 张）调用视觉模型（qwen3.7-plus）生成标签
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
            // 只处理图片（排除视频），取最近拍摄的
            // 注意：截图也纳入打标范围——用户经常需要搜"短信"、"聊天记录"、"付款截图"这类
            // 只存在于截图里的内容，排除截图会导致这类搜索永远找不到结果
            val allImages = mediaRepo.getAllMedia().first()
                .filter { it.mediaType == MediaType.IMAGE }
                .take(MAX_BATCH_TOTAL)

            // 跳过已打标的照片（幂等：重复运行不会产生重复标签）
            val taggedUris = tagRepo.getAllTaggedUris().toSet()
            val untagged   = allImages.filter { it.uri.toString() !in taggedUris }

            if (untagged.isEmpty()) return Result.success()

            // 提升为前台任务：展示常驻通知，降低系统在长耗时打标过程中提前杀掉进程的概率，
            // 用户不用打开 App 也能看到打标进度
            setForeground(buildForegroundInfo(0, untagged.size))

            // 分批打标：有限并发调用 AI（每批 BATCH_SIZE 张，同时跑 CONCURRENCY 个批次），
            // MAX_BATCH_TOTAL=10000 时批次数可达约 2000 个，顺序请求耗时过长，
            // 用 Semaphore 控制并发，避免触发 API 限流；进度计数用 Mutex 保护，通知更新无需加锁外的额外同步。
            var done = 0
            val progressMutex = Mutex()
            val semaphore = Semaphore(CONCURRENCY)
            coroutineScope {
                untagged.chunked(BATCH_SIZE).map { batch ->
                    async {
                        semaphore.withPermit {
                            val results = aiRepo.tagPhotoBatch(batch)
                            for (r in results) {
                                tagRepo.saveTags(r.uri, r.tags)
                                r.ocrText?.let { tagRepo.saveOcrText(r.uri, it) }
                            }
                            progressMutex.withLock {
                                done += batch.size
                                setForeground(buildForegroundInfo(done, untagged.size))
                            }
                        }
                    }
                }.awaitAll()
            }
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("PhotoTagWorker", "打标任务失败: ${e.message}")
            Result.failure()
        }
    }

    /** 构建/更新前台通知，展示"已打标 X / 共 Y 张"的实时进度 */
    private fun buildForegroundInfo(done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI 智能相册打标", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "AI 正在后台识别照片场景并分类"
                }
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("AI 智能相册")
            .setContentText(if (total > 0) "正在整理照片 ($done/$total)" else "正在准备…")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true) }
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        /** 单次任务最多处理的图片数量（控制 API 成本） */
        private const val MAX_BATCH_TOTAL = 10_000
        /** 每批图片数（5 张一批，与废片清理/搜索兜底保持一致） */
        private const val BATCH_SIZE = 5
        /** 同时在途的批次请求数，参见 WasteCleanupViewModel 中的同款设计说明 */
        private const val CONCURRENCY = 5
        /** WorkManager 唯一任务名 */
        const val WORK_NAME = "ai_photo_tag"
        /** 前台通知渠道 ID */
        private const val CHANNEL_ID = "ai_photo_tag_progress"
        /** 前台通知 ID（进程内唯一即可） */
        private const val NOTIFICATION_ID = 1001
    }
}
