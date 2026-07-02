package com.example.aigallery.data.mediastore

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import com.example.aigallery.domain.model.Album
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.domain.repository.IMediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStore 数据层实现
 *
 * 通过 Android ContentResolver 查询本地媒体库（图片和视频）。
 *
 * ⚠️ 核心原则：
 * 1. 投影（projection）只包含元数据列，绝不查询 DATA 列（像素路径）
 * 2. 所有查询运行在 IO 线程（flowOn(Dispatchers.IO)）
 * 3. 使用 Cursor.use{} 确保 Cursor 一定被关闭，防止资源泄漏
 * 4. 列索引在循环外预取（getColumnIndexOrThrow 只调用一次），避免 N 次 JNI 调用
 * 5. 使用 callbackFlow + ContentObserver 实现响应式更新
 */
@Singleton
class MediaStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : IMediaRepository {

    // ============================================================
    // 查询投影（只要元数据，不要像素）
    // ============================================================

    /** 图片查询列（不含像素 DATA 路径，使用 content:// URI 代替） */
    private val imageProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_ADDED,      // 单位：秒
        MediaStore.MediaColumns.DATE_TAKEN,      // 单位：毫秒（可能为 null）
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        // 注意：不在 projection 里放 "is_motion_photo"。
        // 该列为 Samsung 私有扩展，非标准 AOSP API；把它放入 projection 会导致非三星设备
        // 的 ContentResolver.query() 抛出 SQLiteException（未知列名）。
        // 转而在 cursor 返回后用 getColumnIndex 软探测：三星设备返回正常索引，
        // 其他设备返回 -1，代码中已用 (col != -1) 判断，自动安全降级。
    )

    /** 视频查询列（在图片列基础上增加 DURATION） */
    private val videoProjection = imageProjection + arrayOf(
        MediaStore.Video.VideoColumns.DURATION   // 单位：毫秒
    )

    // ============================================================
    // getAllMedia：获取全部媒体，响应式 Flow
    // ============================================================

    override fun getAllMedia(): Flow<List<MediaItem>> = callbackFlow {
        // 立即查询并发射初始数据
        trySend(queryAllMedia())

        // 捕获当前 ProducerScope（也是 CoroutineScope），供 ContentObserver 使用
        val scope = this

        // 注册 ContentObserver，监听图片/视频库变化（拍照、删除、从其他 App 导入等）
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                // ContentObserver 回调在 binder 线程，需切到协程执行查询
                scope.launch(Dispatchers.IO) {
                    trySend(queryAllMedia())
                }
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )

        // Flow 被取消时自动注销 Observer，防止内存泄漏
        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
        }

    }.flowOn(Dispatchers.IO) // 整个 Flow 在 IO 线程执行，不阻塞主线程

    // ============================================================
    // getAlbums：相册列表（从全量媒体数据归纳）
    // ============================================================

    override fun getAlbums(): Flow<List<Album>> = callbackFlow {
        trySend(queryAlbums())

        val scope = this
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                scope.launch(Dispatchers.IO) { trySend(queryAlbums()) }
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )

        awaitClose { context.contentResolver.unregisterContentObserver(observer) }

    }.flowOn(Dispatchers.IO)

    // ============================================================
    // getMediaByAlbum：指定相册的媒体
    // ============================================================

    override fun getMediaByAlbum(bucketId: Long): Flow<List<MediaItem>> = callbackFlow {
        trySend(queryMediaByBucket(bucketId))

        val scope = this
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                scope.launch(Dispatchers.IO) { trySend(queryMediaByBucket(bucketId)) }
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )

        awaitClose { context.contentResolver.unregisterContentObserver(observer) }

    }.flowOn(Dispatchers.IO)

    // ============================================================
    // 私有：查询所有媒体（图片 + 视频合并）
    // ============================================================

    private fun queryAllMedia(): List<MediaItem> {
        val images = queryMedia(
            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection = imageProjection,
            selection = null,
            selectionArgs = null,
            mediaType = MediaType.IMAGE
        )
        val videos = queryMedia(
            contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection = videoProjection,
            selection = null,
            selectionArgs = null,
            mediaType = MediaType.VIDEO
        )

        // 实况照片配对：以「相册 ID + 文件基名（不含扩展名）」为键，建立视频快速查找表
        // 覆盖 Samsung Live Photo、Apple Live Photo 导入、部分 Motion Photo 方案
        val videoPairMap: Map<Pair<Long, String>, android.net.Uri> = buildMap {
            for (video in videos) {
                val baseName = video.name.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
                put(Pair(video.bucketId, baseName), video.uri)
            }
        }

        // 为每张图片尝试匹配同相册同基名的视频
        val pairedImages = images.map { image ->
            val baseName = image.name.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
            val pairUri = videoPairMap[Pair(image.bucketId, baseName)]
            if (pairUri != null) image.copy(livePairUri = pairUri) else image
        }

        // 合并图片和视频，按「有效时间」降序排列
        return (pairedImages + videos).sortedByDescending { item ->
            if (item.dateTaken > 0) item.dateTaken else item.dateAdded
        }
    }

    // ============================================================
    // 私有：查询指定相册的媒体
    // ============================================================

    private fun queryMediaByBucket(bucketId: Long): List<MediaItem> {
        val selection = "${MediaStore.MediaColumns.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId.toString())
        val images = queryMedia(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection, selection, selectionArgs, MediaType.IMAGE
        )
        val videos = queryMedia(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection, selection, selectionArgs, MediaType.VIDEO
        )
        return (images + videos).sortedByDescending { it.dateAdded }
    }

    // ============================================================
    // 私有：从相册列表归纳 Album 列表
    // ============================================================

    private fun queryAlbums(): List<Album> {
        // 利用已有的媒体查询结果，按 bucketId 分组归纳
        val allMedia = queryAllMedia()

        return allMedia
            .groupBy { it.bucketId }
            .map { (bucketId, items) ->
                val newest = items.first() // 列表已按时间降序，第一个即最新封面
                Album(
                    id = bucketId,
                    name = newest.bucketName.ifBlank { "其他" },
                    coverUri = newest.uri,
                    mediaCount = items.size,
                    lastModified = newest.dateAdded
                )
            }
            .sortedByDescending { it.mediaCount } // 媒体最多的相册排前面
    }

    // ============================================================
    // 核心私有方法：执行实际的 ContentResolver 查询
    // ============================================================

    /**
     * 查询 MediaStore 并将 Cursor 转换为 MediaItem 列表
     *
     * @param contentUri   查询的 URI（图片 URI 或视频 URI）
     * @param projection   要查询的列（只含元数据）
     * @param selection    WHERE 条件（null 表示全量）
     * @param selectionArgs WHERE 条件参数（防 SQL 注入）
     * @param mediaType    本次查询的媒体类型
     */
    private fun queryMedia(
        contentUri: android.net.Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        mediaType: MediaType
    ): List<MediaItem> {
        val results = mutableListOf<MediaItem>()

        // 按 DATE_ADDED 降序，确保最新媒体在前
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        // Cursor.use{} 确保 Cursor 一定被关闭，即使查询中途抛异常
        context.contentResolver.query(
            contentUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->

            // ---- 列索引预取（在循环外执行一次，提升性能）----
            val colId          = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val colName        = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val colDateAdded   = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val colDateTaken   = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)  // 可能不存在
            val colMimeType    = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val colWidth       = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val colHeight      = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val colSize        = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val colBucketId    = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
            val colBucketName  = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val colDuration    = if (mediaType == MediaType.VIDEO)
                cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
            val colIsMotionPhoto = if (mediaType == MediaType.IMAGE)
                cursor.getColumnIndex("is_motion_photo") else -1

            // ---- 逐行读取 Cursor ----
            while (cursor.moveToNext()) {
                val id = cursor.getLong(colId)

                // 用 ContentUris 构建标准 content:// URI，Coil3 能直接加载
                val uri = ContentUris.withAppendedId(contentUri, id)

                // DATE_ADDED 单位是"秒"，转换为毫秒与 DATE_TAKEN 统一
                val dateAdded = cursor.getLong(colDateAdded) * 1000L

                // DATE_TAKEN 可能为 null（老文件或非相机拍摄），返回 0 表示未知
                val dateTaken = if (colDateTaken != -1) cursor.getLong(colDateTaken) else 0L

                results.add(
                    MediaItem(
                        id         = id,
                        uri        = uri,
                        name       = cursor.getString(colName) ?: "",
                        dateAdded  = dateAdded,
                        dateTaken  = dateTaken,
                        mimeType   = cursor.getString(colMimeType) ?: "",
                        mediaType  = mediaType,
                        width      = if (colWidth  != -1) cursor.getInt(colWidth)  else 0,
                        height     = if (colHeight != -1) cursor.getInt(colHeight) else 0,
                        size       = cursor.getLong(colSize),
                        duration   = if (colDuration != -1) cursor.getLong(colDuration) else 0L,
                        bucketId      = if (colBucketId   != -1) cursor.getLong(colBucketId)    else 0L,
                        bucketName    = if (colBucketName != -1) cursor.getString(colBucketName) ?: "其他" else "其他",
                        isMotionPhoto = colIsMotionPhoto != -1 && cursor.getInt(colIsMotionPhoto) == 1
                    )
                )
            }
        }

        return results
    }

    // ----------------------------------------------------------------
    // 删除请求
    // ----------------------------------------------------------------

    /**
     * 构建 Android 系统删除确认请求
     *
     * Android 10+ 不允许 App 直接删除其他应用创建的媒体文件。
     * 必须调用 [MediaStore.createDeleteRequest]，弹出系统授权弹窗，
     * 用户点击"允许"后系统才执行实际删除，MediaStore 随即触发响应式刷新。
     *
     * minSdk=34（Android 14），无需 Build.VERSION 版本判断，直接调用。
     *
     * @param uris 要删除的媒体 URI 列表
     * @return 系统弹窗 IntentSender（传给 ActivityResultLauncher 启动）
     */
    override suspend fun buildDeleteRequest(uris: List<Uri>): IntentSender? {
        if (uris.isEmpty()) return null
        return MediaStore.createDeleteRequest(
            context.contentResolver,
            uris
        ).intentSender
    }
}
