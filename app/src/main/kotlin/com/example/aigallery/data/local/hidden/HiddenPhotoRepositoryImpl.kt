package com.example.aigallery.data.local.hidden

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.aigallery.data.local.db.HiddenPhotoDao
import com.example.aigallery.data.local.db.HiddenPhotoEntity
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.domain.repository.IHiddenPhotoRepository
import com.example.aigallery.domain.repository.PreparedHide
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隐藏相册 Repository 实现：目录隔离方案
 *
 * 私有目录：getExternalFilesDir(null)/.hidden_photos/
 * - 属于应用私有外部存储，Android 10+ 分区存储下其他 App 无法访问，也不会被 MediaStore 索引，
 *   天然满足"从主相册流移除" + "避免被第三方 App 扫描"这两个目标。
 * - 卸载应用时会随之清空（符合"隐藏数据是 App 内部资产"的直觉，无需额外清理逻辑）。
 *
 * 通过 [FileProvider] 把私有文件包装成 content:// URI，复用现有 Coil 加载 / 详情页播放逻辑，
 * 不需要为隐藏相册单独写一套图片/视频加载代码。
 */
@Singleton
class HiddenPhotoRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: HiddenPhotoDao
) : IHiddenPhotoRepository {

    private val hiddenDir: File by lazy {
        File(context.getExternalFilesDir(null), HIDDEN_DIR_NAME).apply { mkdirs() }
    }

    override fun getHiddenPhotos(): Flow<List<MediaItem>> =
        dao.getAll().map { list -> list.map { it.toMediaItem() } }

    override fun getHiddenCount(): Flow<Int> = dao.getCount()

    override suspend fun prepareHide(items: List<MediaItem>): List<PreparedHide> =
        withContext(Dispatchers.IO) {
            items.mapNotNull { item ->
                try {
                    val destFile = File(hiddenDir, uniqueFileName(item))
                    context.contentResolver.openInputStream(item.uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@mapNotNull null

                    val entity = HiddenPhotoEntity(
                        hiddenFilePath     = destFile.absolutePath,
                        displayName        = item.name,
                        mimeType           = item.mimeType,
                        isVideo            = item.mediaType == MediaType.VIDEO,
                        dateTaken          = if (item.dateTaken > 0) item.dateTaken else item.dateAdded,
                        width              = item.width,
                        height             = item.height,
                        durationMs         = item.duration,
                        originalBucketName = item.bucketName
                    )
                    PreparedHide(entity = entity, originalUri = item.uri)
                } catch (e: Exception) {
                    android.util.Log.w("HiddenPhoto", "隐藏准备失败 uri=${item.uri}: ${e.message}")
                    null
                }
            }
        }

    override suspend fun buildDeleteRequest(uris: List<Uri>): IntentSender? {
        if (uris.isEmpty()) return null
        return MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
    }

    override suspend fun commitHide(batch: List<HiddenPhotoEntity>) = withContext(Dispatchers.IO) {
        dao.insertAll(batch)
    }

    override suspend fun rollbackHide(batch: List<HiddenPhotoEntity>) = withContext(Dispatchers.IO) {
        batch.forEach { runCatching { File(it.hiddenFilePath).delete() } }
        Unit
    }

    override suspend fun restore(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        // FileProvider URI 的最后一段路径片段就是私有目录下的文件名（见 toMediaItem 的构造方式），
        // 拼回 hiddenDir 即可还原完整绝对路径，不需要额外维护一份 uri -> path 映射。
        val paths = uris.mapNotNull { uri -> uri.lastPathSegment?.let { File(hiddenDir, it).absolutePath } }
        var success = 0
        val entities = dao.getByPaths(paths)
        for (entity in entities) {
            try {
                val file = File(entity.hiddenFilePath)
                if (!file.exists()) {
                    dao.deleteByPath(entity.hiddenFilePath)
                    continue
                }

                val collection = if (entity.isVideo)
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val relativePath = if (entity.isVideo) "Movies/AIGallery恢复" else "Pictures/AIGallery恢复"

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, entity.displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, entity.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.DATE_TAKEN, entity.dateTaken)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val newUri = context.contentResolver.insert(collection, values) ?: continue

                val outputStream = context.contentResolver.openOutputStream(newUri)
                if (outputStream == null) {
                    context.contentResolver.delete(newUri, null, null)
                    continue
                }
                outputStream.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(newUri, values, null, null)

                file.delete()
                dao.deleteByPath(entity.hiddenFilePath)
                success++
            } catch (e: Exception) {
                android.util.Log.w("HiddenPhoto", "恢复失败 path=${entity.hiddenFilePath}: ${e.message}")
            }
        }
        success
    }

    /** 用时间戳 + 原文件名拼接，避免同名文件互相覆盖，同时保留后缀名供 MIME 识别 */
    private fun uniqueFileName(item: MediaItem): String {
        val safeName = item.name.replace(Regex("[/\\\\]"), "_").ifBlank { "file" }
        return "${System.currentTimeMillis()}_${item.id}_$safeName"
    }

    private fun HiddenPhotoEntity.toMediaItem(): MediaItem {
        val file = File(hiddenFilePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return MediaItem(
            id         = hiddenFilePath.hashCode().toLong(),
            uri        = uri,
            name       = displayName,
            dateAdded  = hiddenAt,
            dateTaken  = dateTaken,
            mimeType   = mimeType,
            mediaType  = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
            width      = width,
            height     = height,
            size       = file.length(),
            duration   = durationMs,
            bucketId   = HIDDEN_BUCKET_ID,
            bucketName = "隐藏相册"
        )
    }

    companion object {
        private const val HIDDEN_DIR_NAME = ".hidden_photos"
        /** 隐藏相册的固定虚拟 bucketId，与真实 MediaStore bucketId（均为正数哈希）区分 */
        private const val HIDDEN_BUCKET_ID = -1L
    }
}
