package com.example.aigallery.data.mediastore

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * 单文件内嵌式实况照片视频提取工具
 *
 * 荣耀/华为（MagicOS/HarmonyOS）、Samsung、Google Pixel 等设备的实况照片
 * 以单一 HEIC/JPEG 文件存储，视频数据以 MP4 格式附加在图片数据之后：
 *
 *   [ HEIC/JPEG 图片数据 ] + [ MP4 视频数据（以 ftyp box 起始） ]
 *
 * 本工具从文件末尾向前搜索 MP4 ftyp box 的魔术字节，提取视频部分并写入
 * App 缓存目录的临时 .mp4 文件，供 ExoPlayer 播放。
 */
object MotionPhotoHelper {

    private const val TAG = "MotionPhotoHelper"

    /** MP4 ftyp box 的 ASCII 字节序列：'f'(0x66) 't'(0x74) 'y'(0x79) 'p'(0x70) */
    private val FTYP_BYTES = byteArrayOf(0x66, 0x74, 0x79, 0x70)

    /**
     * 从内嵌式实况照片中提取 MP4 视频，缓存到 App 临时目录后返回 Uri。
     *
     * 算法：
     * 1. 读取文件末尾最多 6 MB（涵盖大多数 3 秒实况视频）
     * 2. 从缓冲区末尾向前搜索最后一个 ftyp 魔术字节
     *    - 取"最后一个"是为了在 HEIC 文件中跳过 HEIC 自身容器开头的 ftyp，
     *      只找附加在末尾的嵌入视频的 ftyp
     * 3. MP4 box 的完整起始位置 = ftyp 偏移 − 4（box-size 字段占 4 字节）
     * 4. 提取该位置到文件末尾的字节写入临时文件并缓存
     *
     * @param context  Android Context（用于打开 ContentResolver 和访问缓存目录）
     * @param imageUri 实况照片的 content:// URI
     * @return 临时 MP4 文件的 file:// Uri，或 null（非实况照片或提取失败）
     */
    fun extractEmbeddedVideo(context: Context, imageUri: Uri): Uri? {
        // 以 URI 字符串哈希值命名缓存文件，避免重复提取
        val cacheKey = imageUri.toString().hashCode()
        val tempFile = File(context.cacheDir, "live_$cacheKey.mp4")

        // 缓存命中且有效（大于 1 KB），直接返回
        if (tempFile.exists() && tempFile.length() > 1_024L) {
            return Uri.fromFile(tempFile)
        }

        return try {
            context.contentResolver.openFileDescriptor(imageUri, "r")?.use { pfd ->
                val fileSize = pfd.statSize
                if (fileSize < 50_000L) return null  // 文件过小，排除非实况照片

                // 读取末尾最多 6 MB
                val searchLen = minOf(6_000_000L, fileSize).toInt()
                val buf = ByteArray(searchLen)

                FileInputStream(pfd.fileDescriptor).use { fis ->
                    // 跳到搜索起点，循环 skip 确保精确定位
                    var toSkip = fileSize - searchLen
                    while (toSkip > 0) {
                        val skipped = fis.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                    // 完整读取搜索缓冲区
                    var offset = 0
                    while (offset < buf.size) {
                        val n = fis.read(buf, offset, buf.size - offset)
                        if (n < 0) break
                        offset += n
                    }
                }

                // 从缓冲区末尾向前找最后一个 ftyp（跳过 HEIC 容器自身的 ftyp）
                val ftypPos = lastIndexOf(buf, FTYP_BYTES)
                if (ftypPos < 4) {
                    Log.w(TAG, "ftyp box not found in last ${searchLen / 1_000}KB of $imageUri")
                    return null
                }

                // ftyp 前 4 字节是 MP4 box-size 字段，整个 box 从此处开始
                val mp4Slice = buf.copyOfRange(ftypPos - 4, buf.size)

                // 写入缓存（先写临时文件再 rename，防止写入到一半时返回半损坏文件）
                val tmpWrite = File(context.cacheDir, "live_${cacheKey}_tmp.mp4")
                tmpWrite.writeBytes(mp4Slice)
                tmpWrite.renameTo(tempFile)

                Log.d(TAG, "Extracted ${mp4Slice.size / 1_024}KB video from $imageUri")
                Uri.fromFile(tempFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract embedded video from $imageUri", e)
            null
        }
    }

    /**
     * 从 data 数组末尾向前搜索 pattern 首次出现的索引（即最右侧匹配位置）。
     *
     * 从末尾搜索是为了在 HEIC 这类 ISOBMFF 容器文件中
     * 跳过文件开头 HEIC 格式自己的 ftyp，找到末尾附加的嵌入视频的 ftyp。
     *
     * @return pattern 在 data 中的起始索引；未找到时返回 -1
     */
    private fun lastIndexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in (data.size - pattern.size) downTo 0) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
