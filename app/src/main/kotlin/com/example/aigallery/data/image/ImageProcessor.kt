package com.example.aigallery.data.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/**
 * 本地图像处理算法（纯离线，无需联网）
 *
 * ⚠️ 智能抠图 / AI 消除 / 老照片修复 / AI 照片美化已改为调用大模型实现，
 * 详见 [com.example.aigallery.data.ai.AiImageEditRepositoryImpl]。
 * 此处只保留真正的本地功能：打马赛克、裁剪、涂鸦。
 */
object ImageProcessor {

    /** 对整张图片做块状像素化（马赛克效果），供预览和最终处理复用 */
    fun pixelate(source: Bitmap, blockSize: Int = 24): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
                for (dy in 0 until blockSize) {
                    val py = y + dy
                    if (py >= h) break
                    for (dx in 0 until blockSize) {
                        val px = x + dx
                        if (px >= w) break
                        val c = pixels[py * w + px]
                        rSum += Color.red(c); gSum += Color.green(c); bSum += Color.blue(c)
                        count++
                    }
                }
                if (count > 0) {
                    val avg = Color.rgb(rSum / count, gSum / count, bSum / count)
                    for (dy in 0 until blockSize) {
                        val py = y + dy
                        if (py >= h) break
                        for (dx in 0 until blockSize) {
                            val px = x + dx
                            if (px >= w) break
                            out[py * w + px] = avg
                        }
                    }
                }
                x += blockSize
            }
            y += blockSize
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** 打马赛克：仅对遮罩标记区域应用像素化效果，其余区域保持原样 */
    fun mosaic(source: Bitmap, mask: Bitmap, blockSize: Int = 24): Bitmap {
        val w = source.width
        val h = source.height
        val pixelated = pixelate(source, blockSize)

        val scaledMask = if (mask.width != w || mask.height != h) {
            Bitmap.createScaledBitmap(mask, w, h, true)
        } else mask

        val srcPixels = IntArray(w * h)
        val pixelPixels = IntArray(w * h)
        val maskPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)
        pixelated.getPixels(pixelPixels, 0, w, 0, 0, w, h)
        scaledMask.getPixels(maskPixels, 0, w, 0, 0, w, h)

        val out = IntArray(w * h)
        for (i in out.indices) {
            out[i] = if (Color.alpha(maskPixels[i]) >= 128) pixelPixels[i] else srcPixels[i]
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        pixelated.recycle()
        if (scaledMask !== mask) scaledMask.recycle()
        return result
    }

    /** 裁剪 Bitmap */
    fun crop(source: Bitmap, left: Int, top: Int, width: Int, height: Int): Bitmap {
        val l = left.coerceIn(0, source.width - 1)
        val t = top.coerceIn(0, source.height - 1)
        val w = width.coerceIn(1, source.width - l)
        val h = height.coerceIn(1, source.height - t)
        return Bitmap.createBitmap(source, l, t, w, h)
    }

    /** 合并涂鸦层到原图 */
    fun mergeDoodle(base: Bitmap, doodle: Bitmap): Bitmap {
        val result = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).drawBitmap(doodle, 0f, 0f, null)
        return result
    }
}
