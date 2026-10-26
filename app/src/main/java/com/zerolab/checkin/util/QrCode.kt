package com.zerolab.checkin.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * v1.4.2 优化：扫码打卡的二维码生成与保存（从 CreateItemActivity 抽出，便于复用与测试）。
 */
object QrCode {

    private const val SIZE = 600

    /** 生成二维码位图（ZXing，600×600）；非法内容返回 null */
    fun bitmap(content: String): Bitmap? = try {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, SIZE, SIZE,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        for (x in 0 until SIZE) for (y in 0 until SIZE) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        bmp
    } catch (_: Exception) {
        null
    }

    /** 保存到相册（API 29+ 走 MediaStore 无需权限；API 24–28 走 insertImage） */
    fun saveToGallery(ctx: Context, bmp: Bitmap, content: String) {
        fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        try {
            val name = "checkin_qr_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/打卡APP")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    toast("二维码已保存到相册（Pictures/打卡APP）")
                } else toast("保存失败")
            } else {
                val url = MediaStore.Images.Media.insertImage(ctx.contentResolver, bmp, name, content)
                toast(if (url != null) "二维码已保存到相册" else "保存失败")
            }
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
        }
    }
}
