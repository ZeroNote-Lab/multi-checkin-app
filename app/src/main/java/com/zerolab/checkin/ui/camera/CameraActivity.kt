package com.zerolab.checkin.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zerolab.checkin.R
import java.io.File
import java.util.concurrent.Executors

/**
 * v1.3.5 应用内相机：预览与拍照统一锁定传感器原生比例（竖屏 3:4）。
 * 替代系统相机（ACTION_IMAGE_CAPTURE 无法指定比例，部分机型默认全屏）。
 *
 * 输出：拍照确认后 setResult(OK)，extra "path" 为 jpg 文件绝对路径；取消 RESULT_CANCELED。
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var confirmPreview: ImageView
    private lateinit var captureBar: View
    private lateinit var confirmBar: View
    private lateinit var tvHint: TextView
    private lateinit var btnFlash: TextView

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var capturedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏、竖屏
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.preview)
        confirmPreview = findViewById(R.id.confirm_preview)
        captureBar = findViewById(R.id.capture_bar)
        confirmBar = findViewById(R.id.confirm_bar)
        tvHint = findViewById(R.id.tv_hint)
        btnFlash = findViewById(R.id.btn_flash)

        // 预览 / 确认图都按 3:4（竖屏宽:高 = 3:4）
        val dm = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val w = dm.widthPixels
        val h = (w * 4f / 3f).toInt().coerceAtMost(dm.heightPixels - dp(180))
        previewView.layoutParams = previewView.layoutParams.apply { this.width = w; this.height = h }
        confirmPreview.layoutParams = confirmPreview.layoutParams.apply { this.width = w; this.height = h }

        findViewById<TextView>(R.id.btn_cancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_shutter).setOnClickListener { takePhoto() }
        findViewById<TextView>(R.id.btn_switch).setOnClickListener { switchCamera() }
        btnFlash.setOnClickListener { toggleFlash() }
        findViewById<TextView>(R.id.btn_retake).setOnClickListener { retake() }
        findViewById<TextView>(R.id.btn_use).setOnClickListener { usePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val capture = ImageCapture.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(flashMode)
                    .build()
                imageCapture = capture

                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
                tvHint.visibility = View.GONE
                previewView.visibility = View.VISIBLE
            } catch (e: Exception) {
                tvHint.text = "无法启动相机：${e.message ?: "未知错误"}"
                tvHint.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera()
    }

    private fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        btnFlash.text = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "⚡"
            ImageCapture.FLASH_MODE_AUTO -> "🅰"
            else -> "⚡"
        }
        btnFlash.alpha = if (flashMode == ImageCapture.FLASH_MODE_OFF) 1f else 1f
        Toast.makeText(this,
            when (flashMode) { ImageCapture.FLASH_MODE_ON -> "闪光灯常开"; ImageCapture.FLASH_MODE_AUTO -> "闪光灯自动"; else -> "闪光灯关闭" },
            Toast.LENGTH_SHORT).show()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val out = File(cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(out).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                capturedFile = out
                runOnUiThread { showConfirm(out) }
            }
            override fun onError(exc: ImageCaptureException) {
                runOnUiThread { Toast.makeText(this@CameraActivity, "拍照失败：${exc.message}", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun showConfirm(file: File) {
        confirmPreview.setImageBitmap(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
        previewView.visibility = View.GONE
        captureBar.visibility = View.GONE
        btnFlash.visibility = View.GONE
        findViewById<View>(R.id.btn_switch).visibility = View.GONE
        confirmPreview.visibility = View.VISIBLE
        confirmBar.visibility = View.VISIBLE
    }

    private fun retake() {
        capturedFile?.delete()
        capturedFile = null
        confirmPreview.setImageDrawable(null)
        confirmPreview.visibility = View.GONE
        confirmBar.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        captureBar.visibility = View.VISIBLE
        btnFlash.visibility = View.VISIBLE
        findViewById<View>(R.id.btn_switch).visibility = View.VISIBLE
    }

    private fun usePhoto() {
        val f = capturedFile ?: return
        val intent = android.content.Intent().putExtra("path", f.absolutePath)
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
