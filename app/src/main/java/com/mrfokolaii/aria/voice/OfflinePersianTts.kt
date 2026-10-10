package com.mrfokolaii.aria.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.TimeUnit

/**
 * Fully offline Persian TTS using a Piper ONNX model stored inside the app sandbox.
 *
 * First launch (or via settings): download model once while online.
 * Afterwards: synthesize without network.
 *
 * Model: MahtaFetrat/Mana-Persian-Piper (fa_IR-mana-medium)
 */
class OfflinePersianTts(private val context: Context) {

    private val modelDir = File(context.filesDir, "offline_tts_fa").also { it.mkdirs() }
    private val modelFile = File(modelDir, "fa_IR-mana-medium.onnx")
    private val configFile = File(modelDir, "fa_IR-mana-medium.onnx.json")

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var sampleRate = 22050
    private var phonemeIdMap: Map<String, Int> = emptyMap()
    private var noiseScale = 0.667f
    private var lengthScale = 1.0f
    private var noiseW = 0.8f
    private var ready = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun isModelDownloaded(): Boolean =
        modelFile.exists() && modelFile.length() > 1_000_000 && configFile.exists()

    fun isReady(): Boolean = ready && session != null

    fun modelPath(): String = modelDir.absolutePath

    /** Load model into memory (call after download or on app start). */
    @Synchronized
    fun prepare(): Boolean {
        if (ready && session != null) return true
        if (!isModelDownloaded()) return false
        return try {
            val json = JSONObject(configFile.readText())
            sampleRate = json.optInt("audio", JSONObject()).let {
                // piper config: audio.sample_rate
                json.optJSONObject("audio")?.optInt("sample_rate", 22050) ?: 22050
            }
            val infer = json.optJSONObject("inference")
            noiseScale = infer?.optDouble("noise_scale", 0.667)?.toFloat() ?: 0.667f
            lengthScale = infer?.optDouble("length_scale", 1.0)?.toFloat() ?: 1.0f
            noiseW = infer?.optDouble("noise_w", 0.8)?.toFloat() ?: 0.8f

            val mapObj = json.optJSONObject("phoneme_id_map")
            val map = mutableMapOf<String, Int>()
            if (mapObj != null) {
                val keys = mapObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = mapObj.optJSONArray(k)
                    if (arr != null && arr.length() > 0) {
                        map[k] = arr.getInt(0)
                    } else {
                        map[k] = mapObj.optInt(k, 0)
                    }
                }
            }
            phonemeIdMap = map

            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env!!.createSession(modelFile.absolutePath, opts)
            ready = true
            Log.i(TAG, "Offline FA TTS ready sr=$sampleRate phonemes=${phonemeIdMap.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            ready = false
            false
        }
    }

    /**
     * Download model files (needs internet once). Returns true on success.
     */
    fun downloadModel(onProgress: ((String) -> Unit)? = null): Boolean {
        return try {
            onProgress?.invoke("در حال دانلود مدل فارسی...")
            // HuggingFace resolve URLs for Mana Persian Piper
            val base =
                "https://huggingface.co/MahtaFetrat/Mana-Persian-Piper/resolve/main"
            downloadTo("$base/fa_IR-mana-medium.onnx", modelFile, onProgress)
            // config may be named differently — try common names
            val configUrls = listOf(
                "$base/fa_IR-mana-medium.onnx.json",
                "$base/config.json",
                "https://huggingface.co/gyroing/Persian-Piper-Model-gyro/resolve/main/fa_IR-gyro-medium.onnx.json"
            )
            var configOk = false
            for (u in configUrls) {
                try {
                    downloadTo(u, configFile, onProgress)
                    if (configFile.length() > 100) {
                        configOk = true
                        break
                    }
                } catch (_: Exception) {}
            }
            if (!configOk) {
                // Minimal fallback config so prepare() can still attempt
                configFile.writeText(
                    """{"audio":{"sample_rate":22050},"inference":{"noise_scale":0.667,"length_scale":1.0,"noise_w":0.8},"phoneme_id_map":{}}"""
                )
            }
            onProgress?.invoke("دانلود تمام شد. در حال آماده‌سازی...")
            prepare()
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            onProgress?.invoke("خطا در دانلود: ${e.message}")
            false
        }
    }

    private fun downloadTo(url: String, dest: File, onProgress: ((String) -> Unit)?) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "ARIA-OfflineTTS/1.0")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw Exception("empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var sum = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        sum += read
                        if (total > 0 && sum % (512 * 1024) < 8192) {
                            val pct = (sum * 100 / total).toInt()
                            onProgress?.invoke("دانلود: $pct%")
                        }
                    }
                }
            }
        }
        Log.i(TAG, "Saved ${dest.name} (${dest.length()} bytes)")
    }

    /**
     * Synthesize Persian text to a WAV file offline. Returns file or null.
     */
    @Synchronized
    fun synthesizeToFile(text: String): File? {
        if (!prepare()) return null
        val sess = session ?: return null
        val ortEnv = env ?: return null

        return try {
            val ids = textToPhonemeIds(text)
            if (ids.isEmpty()) return null

            val inputName = sess.inputNames.firstOrNull() ?: "input"
            // Piper typically: input [1, N] int64, input_lengths [1] int64, scales [3] float
            val names = sess.inputNames.toList()

            val tensors = mutableMapOf<String, OnnxTensor>()
            try {
                // sequence
                val seq = LongArray(ids.size) { ids[it].toLong() }
                val shape = longArrayOf(1, seq.size.toLong())
                tensors[names.getOrElse(0) { "input" }] =
                    OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(seq), shape)

                if (names.size > 1) {
                    tensors[names[1]] =
                        OnnxTensor.createTensor(
                            ortEnv,
                            LongBuffer.wrap(longArrayOf(seq.size.toLong())),
                            longArrayOf(1)
                        )
                }
                if (names.size > 2) {
                    val scales = floatArrayOf(noiseScale, lengthScale, noiseW)
                    tensors[names[2]] =
                        OnnxTensor.createTensor(
                            ortEnv,
                            FloatBuffer.wrap(scales),
                            longArrayOf(3)
                        )
                }

                val results = sess.run(tensors)
                results.use { result ->
                    val value = result.get(0).value
                    val audio = flattenAudio(value) ?: return null
                    val out = File(context.cacheDir, "fa_tts_${System.currentTimeMillis()}.wav")
                    writeWav(out, audio, sampleRate)
                    out
                }
            } finally {
                tensors.values.forEach { it.close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "synthesize failed", e)
            null
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        Thread {
            try {
                val file = synthesizeToFile(text)
                if (file != null) {
                    playWavBlocking(file)
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "speak failed", e)
            } finally {
                onDone?.invoke()
            }
        }.start()
    }

    private fun textToPhonemeIds(text: String): IntArray {
        // Use phoneme_id_map when available; otherwise map characters + pad tokens
        val bos = phonemeIdMap["^"] ?: phonemeIdMap["<bos>"] ?: 1
        val eos = phonemeIdMap["$"] ?: phonemeIdMap["<eos>"] ?: 2
        val pad = phonemeIdMap["_"] ?: phonemeIdMap["<pad>"] ?: 0
        val ids = mutableListOf<Int>()
        ids.add(bos)
        for (ch in text.trim()) {
            val s = ch.toString()
            val id = phonemeIdMap[s]
                ?: phonemeIdMap[ch.lowercaseChar().toString()]
                ?: pad
            ids.add(id)
            // Piper often expects pad between phonemes
            ids.add(pad)
        }
        ids.add(eos)
        return ids.toIntArray()
    }

    private fun flattenAudio(value: Any?): FloatArray? {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                // nested arrays [1][1][N] or [1][N]
                val flat = mutableListOf<Float>()
                fun walk(v: Any?) {
                    when (v) {
                        is FloatArray -> v.forEach { flat.add(it) }
                        is Array<*> -> v.forEach { walk(it) }
                        is Float -> flat.add(v)
                        is Double -> flat.add(v.toFloat())
                    }
                }
                walk(value)
                if (flat.isEmpty()) null else flat.toFloatArray()
            }
            else -> null
        }
    }

    private fun writeWav(file: File, samples: FloatArray, sr: Int) {
        val dataLen = samples.size * 2
        val totalLen = 36 + dataLen
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(totalLen)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(sr)
        buf.putInt(sr * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray())
        buf.putInt(dataLen)
        for (s in samples) {
            val clipped = s.coerceIn(-1f, 1f)
            buf.putShort((clipped * 32767).toInt().toShort())
        }
        FileOutputStream(file).use { it.write(buf.array()) }
    }

    private fun playWavBlocking(file: File) {
        val lock = Object()
        var done = false
        try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    synchronized(lock) { done = true; lock.notifyAll() }
                    release()
                }
                setOnErrorListener { _, _, _ ->
                    synchronized(lock) { done = true; lock.notifyAll() }
                    true
                }
                prepare()
                start()
            }
            synchronized(lock) {
                if (!done) try { lock.wait(120_000) } catch (_: InterruptedException) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "play failed", e)
        }
    }

    fun release() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        ready = false
    }

    companion object {
        private const val TAG = "OfflinePersianTts"
    }
}
