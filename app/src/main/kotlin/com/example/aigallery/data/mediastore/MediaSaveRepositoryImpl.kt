package com.example.aigallery.data.mediastore

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.example.aigallery.domain.repository.IMediaSaveRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

@UnstableApi
class MediaSaveRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : IMediaSaveRepository {

    override suspend fun saveBitmap(
        bitmap: Bitmap,
        displayName: String,
        mimeType: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AIGallery编辑")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext null

            context.contentResolver.openOutputStream(uri)?.use { out ->
                val format = if (mimeType == "image/png")
                    Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 97
                bitmap.compress(format, quality, out)
            } ?: run {
                context.contentResolver.delete(uri, null, null)
                return@withContext null
            }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun saveVideoTrim(
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        displayName: String
    ): Uri? = withContext(Dispatchers.Main) {
        val outputFile = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        try {
            val mediaItem = MediaItem.Builder()
                .setUri(sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()

            val editedItem = EditedMediaItem.Builder(mediaItem).build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType("video/avc")
                .setAudioMimeType("audio/mp4a-latm")
                .build()

            val resultFile = suspendCancellableCoroutine<File?> { cont ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        cont.resume(outputFile)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        cont.resume(null)
                    }
                })
                transformer.start(editedItem, outputFile.absolutePath)
            } ?: return@withContext null

            insertVideoFile(resultFile, displayName)
        } catch (_: Exception) {
            null
        } finally {
            if (outputFile.exists() && outputFile.length() == 0L) outputFile.delete()
        }
    }

    private suspend fun insertVideoFile(file: File, displayName: String): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/AIGallery编辑")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return@withContext null

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: run {
                    context.contentResolver.delete(uri, null, null)
                    return@withContext null
                }

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                file.delete()
                uri
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun loadBitmap(uri: Uri, maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return@withContext null

            var sampleSize = 1
            val maxSide = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            while (maxSide / sampleSize > maxDim) sampleSize *= 2

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun readImageSize(uri: Uri): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        } catch (_: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------
    // 覆盖原图（Android 11+ 需先经系统写入授权弹窗确认）
    // ----------------------------------------------------------------

    override suspend fun buildWriteRequest(uri: Uri): IntentSender? = withContext(Dispatchers.IO) {
        try {
            MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun overwriteBitmap(uri: Uri, bitmap: Bitmap, mimeType: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val wrote = context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    val format = if (mimeType == "image/png")
                        Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 97
                    bitmap.compress(format, quality, out)
                } ?: false
                wrote
            } catch (_: Exception) {
                false
            }
        }
}
