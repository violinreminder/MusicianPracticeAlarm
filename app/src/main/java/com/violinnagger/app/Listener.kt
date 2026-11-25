package com.violinnagger.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper

/** Listens through the microphone and reports what the Analyzer hears. Needs the mic permission first. */
class Listener(
    private val onResult: (Analyzer.Result) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    fun stop() {
        running = false
    }

    @SuppressLint("MissingPermission")
    fun start() {
        running = true
        Thread {
            val sr = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                main.post { onError("This phone would not give me a microphone.") }
                return@Thread
            }
            val rec: AudioRecord
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sr,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 16384)
                )
            } catch (e: Exception) {
                main.post { onError("I could not open the microphone.") }
                return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                main.post { onError("I could not open the microphone. Is another app using it?") }
                return@Thread
            }
            val analyzer = Analyzer(sr / 2.0)
            val shorts = ShortArray(4096)
            val x = FloatArray(2048)
            try {
                rec.startRecording()
                while (running) {
                    var n = 0
                    while (n < shorts.size && running) {
                        val r = rec.read(shorts, n, shorts.size - n)
                        if (r <= 0) break
                        n += r
                    }
                    if (n < shorts.size) break
                    for (i in 0 until 2048) {
                        x[i] = (shorts[2 * i] + shorts[2 * i + 1]) / 65536f
                    }
                    val res = analyzer.frame(x, shorts.size.toDouble() / sr)
                    if (running) main.post { onResult(res) }
                }
            } catch (e: Exception) {
                main.post { onError("The microphone stopped working.") }
            } finally {
                try {
                    rec.stop()
                } catch (e: Exception) {
                }
                rec.release()
            }
        }.start()
    }
}
