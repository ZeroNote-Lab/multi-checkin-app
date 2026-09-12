package com.zerolab.checkin.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object ImageUtil {
    /** 读取 [src]，等比缩放到最长边 <= maxSide，输出 WebP 到目标目录 */
    fun compressWebp(src: File, outDir: File, maxSide: Int = 480, quality: Int = 65): File? {
        return try {
            if (!outDir.exists()) outDir.mkdirs()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.absolutePath, opts)
            var sample = 1
            val longer = maxOf(opts.outWidth, opts.outHeight)
            while (longer / sample > maxSide * 2) sample *= 2
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(src.absolutePath, decode) ?: return null
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) {
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                if (scaled != bmp) bmp.recycle()
                bmp = scaled
            }
            val out = File(outDir, "p_${System.currentTimeMillis()}.webp")
            FileOutputStream(out).use { fos ->
                bmp.compress(Bitmap.CompressFormat.WEBP, quality, fos)
            }
            bmp.recycle()
            if (out.exists() && out.length() > 0) out else null
        } catch (e: Exception) { null }
    }
}
