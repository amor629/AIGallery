package com.example.aigallery.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.aigallery.R
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.domain.repository.IAiSearchRepository
import com.example.aigallery.domain.repository.IMediaRepository
import com.example.aigallery.domain.repository.IWasteRepository
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
 * 后台 AI 废片扫描 Worker（WorkManager）
 *
 * 架构与 [PhotoTagWorker] 完全一致：以前台服务运行，脱离 ViewModel/页面生命周期，
 * 用户点击"开始扫描"后可立即离开当前页面（甚至退出 App），扫描仍会在后台继续；
 * 每批结果实时持久化到 Room，重新打开废片清理页时通过 [IWasteRepository] 自动展示
 * 最新（含正在进行中的）结果，无需停留在页面等待。
 *
 * 流程：
 * 1. 从 MediaStore 读取所有照片（最多 [MAX_SCAN] 张）
 * 2. 跳过已经扫描过的照片（Room DB 查询，幂等，避免重复消耗 AI 调用）
 * 3. 截图直接标记为"截图"废片，无需调用 AI
 * 4. 非截图照片按拍摄时间临近分批（提升连拍重复检测率），有限并发调用视觉模型
 * 5. 每批结果（含未命中的"正常照片"标记）立即持久化，供 UI 实时展示
 */
class WasteScanWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun mediaRepository(): IMediaRepository
        fun aiSearchRepository(): IAiSearchRepository
        fun wasteRepository(): IWasteRepository
    }

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val mediaRepo = ep.mediaRepository()
        val aiRepo    = ep.aiSearchRepository()
        val wasteRepo = ep.wasteRepository()

        return try {
            val allImages = mediaRepo.getAllMedia().first()
                .filter { it.mediaType == MediaType.IMAGE }
                .take(MAX_SCAN)

            val scannedUris = wasteRepo.getAllScannedUris().toSet()
            val toScan = allImages.filter { it.uri.toString() !in scannedUris }

            if (toScan.isEmpty()) return Result.success()

            val total = toScan.size
            var done = 0
            setForeground(buildForegroundInfo(done, total))

            // ① 截图：直接用文件夹元数据识别，不需要调用 AI
            val screenshots = toScan.filter { it.isScreenshot }
            if (screenshots.isNotEmpty()) {
                wasteRepo.saveResults(screenshots.map { it.uri.toString() to "截图" })
                done += screenshots.size
                setProgress(workDataOf(KEY_SCANNED to done, KEY_TOTAL to total))
                setForeground(buildForegroundInfo(done, total))
            }

            // ② 非截图照片：按拍摄时间临近分批（连拍/重复拍的照片同批比对，提升重复检测率），
            //    有限并发调用 AI，避免上万张时顺序请求耗时过长
            val nonScreenshots = toScan
                .filter { !it.isScreenshot }
                .sortedByDescending { if (it.dateTaken > 0) it.dateTaken else it.dateAdded }
            val batches = buildTemporalBatches(nonScreenshots)

            if (batches.isNotEmpty()) {
                val progressMutex = Mutex()
                val semaphore = Semaphore(CONCURRENCY)
                coroutineScope {
                    batches.map { batch ->
                        async {
                            semaphore.withPermit {
                                val wasteResults = aiRepo.analyzeWasteBatch(batch)
                                val reasonByUri = wasteResults.associate { it.mediaItem.uri.toString() to it.reason }
                                // 批次内所有图片都要落库：命中的记录废片原因，未命中的记录 reason=null（正常照片），
                                // 这样下次扫描才能正确跳过整批，而不仅仅是跳过被判定为废片的那几张
                                val records = batch.map { item ->
                                    val uri = item.uri.toString()
                                    uri to reasonByUri[uri]
                                }
                                wasteRepo.saveResults(records)
                                progressMutex.withLock {
                                    done += batch.size
                                    setProgress(workDataOf(KEY_SCANNED to done, KEY_TOTAL to total))
                                    setForeground(buildForegroundInfo(done, total))
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("WasteScanWorker", "废片扫描任务失败: ${e.message}")
            Result.failure()
        }
    }

    /**
     * 将照片列表按时间临近度分批：
     * - 拍摄时间相差 ≤ [DUPLICATE_WINDOW_MS] 的照片放入同一批次（提升重复检测率）
     * - 每批最多 [BATCH_SIZE] 张，超出则新建批次
     */
    private fun buildTemporalBatches(items: List<MediaItem>): List<List<MediaItem>> {
        if (items.isEmpty()) return emptyList()
        val batches = mutableListOf<MutableList<MediaItem>>()
        var currentBatch = mutableListOf<MediaItem>()
        var prevTime = Long.MIN_VALUE

        for (item in items) {
            val t = if (item.dateTaken > 0) item.dateTaken else item.dateAdded
            val isClose = prevTime != Long.MIN_VALUE && (prevTime - t) <= DUPLICATE_WINDOW_MS
            if (currentBatch.isEmpty() || (isClose && currentBatch.size < BATCH_SIZE)) {
                currentBatch.add(item)
            } else {
                batches.add(currentBatch)
                currentBatch = mutableListOf(item)
            }
            prevTime = t
        }
        if (currentBatch.isNotEmpty()) batches.add(currentBatch)
        return batches
    }

    /** 构建/更新前台通知，展示"已扫描 X / 共 Y 张"的实时进度 */
    private fun buildForegroundInfo(done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI 废片清理", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "AI 正在后台扫描模糊/闭眼/重复照片"
                }
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("AI 废片清理")
            .setContentText(if (total > 0) "正在扫描照片 ($done/$total)" else "正在准备…")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true) }
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        /** 单次任务最多处理的图片数量（控制 API 成本） */
        private const val MAX_SCAN = 10_000
        /** 每批发送给视觉 AI 的图片数，与智能打标/搜索兜底保持一致 */
        private const val BATCH_SIZE = 5
        /** 同时在途的批次请求数，参见 PhotoTagWorker 中的同款设计说明 */
        private const val CONCURRENCY = 5
        /** 判断为"时间相近"（可能是连拍重复）的时间窗口：3 秒 */
        private const val DUPLICATE_WINDOW_MS = 3_000L
        /** WorkManager 唯一任务名 */
        const val WORK_NAME = "ai_waste_scan"
        /** setProgress Data 的 key：已扫描数量 */
        const val KEY_SCANNED = "scanned"
        /** setProgress Data 的 key：计划扫描总数 */
        const val KEY_TOTAL = "total"
        /** 前台通知渠道 ID */
        private const val CHANNEL_ID = "ai_waste_scan_progress"
        /** 前台通知 ID（进程内唯一即可，需与 PhotoTagWorker 的 1001 不同） */
        private const val NOTIFICATION_ID = 1002
    }
}
