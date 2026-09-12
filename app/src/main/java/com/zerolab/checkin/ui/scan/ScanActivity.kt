package com.zerolab.checkin.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.zerolab.checkin.R

/**
 * 真实扫码界面：CameraX 预览 + ZXing 逐帧解码。
 * 扫描内容与预设内容完全一致 → RESULT_OK；不匹配/取消 → RESULT_CANCELED（不打卡）。
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXPECT = "expect"
    }

    private var expectContent = ""
    private var handled = false
    private var analyzing = false
    private lateinit var previewView: PreviewView

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expectContent = intent.getStringExtra(EXTRA_EXPECT) ?: ""
        setContentView(R.layout.activity_scan)
        previewView = findViewById(R.id.pv_preview)
        findViewById<Button>(R.id.btn_scan_cancel).setOnClickListener { finish() }
        val tvHint = findViewById<TextView>(R.id.tv_scan_hint)
        tvHint.text = "将专属二维码对准取景框，自动识别打卡\n预设内容：${expectContent.take(20)}…"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else
            permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy -> decode(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 逐帧解码二维码（仅取 Y 亮度平面，处理 rowStride） */
    private fun decode(proxy: ImageProxy) {
        if (handled) { proxy.close(); return }
        if (analyzing) { proxy.close(); return }
        analyzing = true
        try {
            val yPlane = proxy.planes[0]
            val yBuf = yPlane.buffer
            val yBytes = ByteArray(yBuf.remaining())
            yBuf.get(yBytes)
            val w = proxy.width
            val h = proxy.height
            val stride = yPlane.rowStride
            val luminance = if (stride == w) yBytes else ByteArray(w * h).also { out ->
                for (row in 0 until h) {
                    val src = row * stride
                    val dst = row * w
                    if (src + w <= yBytes.size) System.arraycopy(yBytes, src, out, dst, w)
                }
            }
            val source = PlanarYUVLuminanceSource(luminance, w, h, 0, 0, w, h, false)
            // 每帧新建解码器，避免 MultiFormatReader 状态残留
            val result = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.TRY_HARDER to true))
            }.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            if (result.text.isNotBlank()) {
                val text = result.text
                if (!handled) {
                    handled = true
                    runOnUiThread {
                        if (text == expectContent) {
                            setResult(RESULT_OK, Intent().putExtra("content", text))
                            finish()
                        } else {
                            Toast.makeText(this, "二维码内容不匹配", Toast.LENGTH_SHORT).show()
                            handled = false
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 非二维码帧，忽略继续扫
        } finally {
            analyzing = false
            proxy.close()
        }
    }
}
