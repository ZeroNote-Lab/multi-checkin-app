package com.zerolab.checkin.util

import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** AAC 单声道录音封装，所有异常向上抛由调用方提示，避免闪退 */
class AudioRecorder {
    private var recorder: MediaRecorder? = null
    var outFile: File? = null; private set

    fun start(dir: File, maxSeconds: Int) {
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "v_${System.currentTimeMillis()}.aac")
        outFile = f
        val mr = @Suppress("DEPRECATION") MediaRecorder()
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioSamplingRate(16000)
        mr.setAudioChannels(1)
        mr.setAudioEncodingBitRate(32000)
        mr.setOutputFile(f.absolutePath)
        mr.prepare(); mr.start()
        recorder = mr
    }

    fun stop(): File? {
        return try { recorder?.stop(); recorder?.release(); recorder = null; outFile }
        catch (e: Exception) { recorder?.release(); recorder = null; outFile?.delete(); null }
    }

    fun cancel() { try { recorder?.release() } catch (_: Exception) {}; recorder = null; outFile?.delete() }
}
