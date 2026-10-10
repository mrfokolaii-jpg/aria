package com.mrfokolaii.aria.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.mrfokolaii.aria.util.NetworkUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hybrid TTS priority:
 * 1) Offline neural Persian (Piper ONNX inside app) — no internet
 * 2) System TTS (FA/EN if installed)
 * 3) Online TTS only if network available
 */
class AriaVoiceEngine(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var systemReady = false
    private var systemFaOk = false
    private var systemEnOk = false
    private var mediaPlayer: MediaPlayer? = null
    private val speaking = AtomicBoolean(false)
    private val offlineFa = OfflinePersianTts(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun init() {
        tts = TextToSpeech(context, this)
        // Load neural FA model if already downloaded
        if (offlineFa.isModelDownloaded()) {
            Thread { offlineFa.prepare() }.start()
        }
    }

    fun offlinePersian(): OfflinePersianTts = offlineFa

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            systemReady = false
            return
        }
        systemReady = true
        tts?.setSpeechRate(0.92f)
        tts?.setPitch(1.08f)

        val fa = tts?.setLanguage(Locale("fa", "IR"))
        systemFaOk = fa == TextToSpeech.LANG_AVAILABLE ||
                fa == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                fa == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE

        val en = tts?.setLanguage(Locale.US)
        systemEnOk = en == TextToSpeech.LANG_AVAILABLE ||
                en == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                en == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE

        if (systemFaOk) tts?.language = Locale("fa", "IR")
        else if (systemEnOk) tts?.language = Locale.US
        else tts?.language = Locale.getDefault()
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            onDone?.invoke()
            return
        }
        stop()
        speaking.set(true)
        val online = NetworkUtils.isOnline(context)

        Thread {
            try {
                val segments = splitByScript(clean)
                for ((index, seg) in segments.withIndex()) {
                    if (!speaking.get()) break
                    val isLast = index == segments.lastIndex
                    speakSegment(seg.text, seg.lang, online) {
                        if (isLast) {
                            speaking.set(false)
                            onDone?.invoke()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "speak failed", e)
                speaking.set(false)
                onDone?.invoke()
            }
        }.start()
    }

    private fun speakSegment(text: String, lang: String, online: Boolean, onSegmentDone: () -> Unit) {
        // 1) Neural offline Persian (fully inside app)
        if (lang == "fa" && offlineFa.isReady()) {
            val lock = Object()
            var finished = false
            offlineFa.speak(text) {
                synchronized(lock) { finished = true; lock.notifyAll() }
                onSegmentDone()
            }
            synchronized(lock) {
                if (!finished) try { lock.wait(120_000) } catch (_: InterruptedException) {}
            }
            return
        }

        // 2) System TTS
        val systemOk = when (lang) {
            "fa" -> systemReady && systemFaOk
            else -> systemReady && systemEnOk
        }
        if (systemOk) {
            speakSystem(text, lang, allowOnlineFallback = online, onDone = onSegmentDone)
            return
        }
        if (systemReady) {
            speakSystem(text, if (systemEnOk) "en" else "default", allowOnlineFallback = online, onDone = onSegmentDone)
            return
        }

        // 3) Online
        if (online) {
            speakOnline(text, lang, onSegmentDone)
            return
        }
        Log.w(TAG, "No TTS available offline")
        onSegmentDone()
    }

    private fun speakSystem(
        text: String,
        lang: String,
        allowOnlineFallback: Boolean,
        onDone: () -> Unit
    ) {
        val engine = tts
        if (engine == null) {
            if (allowOnlineFallback) speakOnline(text, lang, onDone) else onDone()
            return
        }
        when (lang) {
            "fa" -> engine.language = Locale("fa", "IR")
            "en" -> engine.language = Locale.US
            else -> engine.language = Locale.getDefault()
        }
        engine.setSpeechRate(0.92f)
        engine.setPitch(1.08f)

        val lock = Object()
        var finished = false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                synchronized(lock) { finished = true; lock.notifyAll() }
                onDone()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                synchronized(lock) { finished = true; lock.notifyAll() }
                if (allowOnlineFallback) speakOnline(text, lang, onDone) else onDone()
            }
        })

        val params = HashMap<String, String>()
        params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = "aria_${System.currentTimeMillis()}"
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params)
        if (result == TextToSpeech.ERROR) {
            if (allowOnlineFallback) speakOnline(text, lang, onDone) else onDone()
            return
        }
        synchronized(lock) {
            if (!finished) try { lock.wait(60_000) } catch (_: InterruptedException) {}
        }
    }

    private fun speakOnline(text: String, lang: String, onDone: () -> Unit) {
        if (!NetworkUtils.isOnline(context)) {
            onDone()
            return
        }
        try {
            for (chunk in chunkText(text, 180)) {
                if (!speaking.get()) break
                val tl = if (lang == "fa") "fa" else "en"
                val encoded = URLEncoder.encode(chunk, "UTF-8")
                val url =
                    "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&q=$encoded&tl=$tl"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .header("Referer", "https://translate.google.com/")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bytes = response.body?.bytes() ?: return@use
                    val tmp = File(context.cacheDir, "aria_tts_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(tmp).use { it.write(bytes) }
                    playFileBlocking(tmp)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Online TTS failed", e)
        } finally {
            onDone()
        }
    }

    private fun playFileBlocking(file: File) {
        val lock = Object()
        var done = false
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    synchronized(lock) { done = true; lock.notifyAll() }
                }
                setOnErrorListener { _, _, _ ->
                    synchronized(lock) { done = true; lock.notifyAll() }
                    true
                }
                prepare()
                start()
            }
            synchronized(lock) {
                if (!done) try { lock.wait(90_000) } catch (_: InterruptedException) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "playFile failed", e)
        }
    }

    fun stop() {
        speaking.set(false)
        try { tts?.stop() } catch (_: Exception) {}
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun shutdown() {
        stop()
        offlineFa.release()
        tts?.shutdown()
        tts = null
    }

    fun statusSummary(): String = buildString {
        append("عصبی FA: ")
        append(if (offlineFa.isReady()) "آماده" else if (offlineFa.isModelDownloaded()) "دانلود شده" else "ندارد")
        append(" | سیستم FA: ")
        append(if (systemFaOk) "دارد" else "ندارد")
        append(" | شبکه: ")
        append(if (NetworkUtils.isOnline(context)) "وصل" else "قطع")
    }

    data class Segment(val text: String, val lang: String)

    companion object {
        private const val TAG = "AriaVoiceEngine"

        fun splitByScript(input: String): List<Segment> {
            val result = mutableListOf<Segment>()
            val sb = StringBuilder()
            var current: String? = null
            fun flush() {
                val t = sb.toString().trim()
                if (t.isNotEmpty() && current != null) result.add(Segment(t, current!!))
                sb.clear()
            }
            for (ch in input) {
                val lang = when {
                    ch.code in 0x0600..0x06FF || ch.code in 0x0750..0x077F ||
                            ch.code in 0xFB50..0xFDFF || ch.code in 0xFE70..0xFEFF -> "fa"
                    ch.isLetter() && ch.code < 0x0250 -> "en"
                    else -> current
                }
                if (lang != null && current != null && lang != current && ch.isLetter()) {
                    flush()
                    current = lang
                    sb.append(ch)
                } else {
                    if (current == null && lang != null) current = lang
                    sb.append(ch)
                }
            }
            flush()
            if (result.isEmpty()) result.add(Segment(input.trim(), "fa"))
            return result
        }

        fun chunkText(text: String, maxLen: Int): List<String> {
            if (text.length <= maxLen) return listOf(text)
            val parts = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                var end = (i + maxLen).coerceAtMost(text.length)
                if (end < text.length) {
                    val space = text.lastIndexOf(' ', end)
                    if (space > i + 20) end = space
                }
                parts.add(text.substring(i, end).trim())
                i = end
            }
            return parts.filter { it.isNotEmpty() }
        }
    }
}
