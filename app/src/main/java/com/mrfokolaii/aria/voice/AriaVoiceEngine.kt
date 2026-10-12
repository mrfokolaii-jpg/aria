package com.mrfokolaii.aria.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
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

class AriaVoiceEngine(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var systemReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val speaking = AtomicBoolean(false)
    private val offlineFa = OfflinePersianTts(context)
    private val initLock = Object()

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun init() {
        tts = TextToSpeech(context, this)
    }

    fun offlinePersian(): OfflinePersianTts = offlineFa

    fun hasSherpaEngine(): Boolean = isPackageInstalled(SHERPA_PKG)

    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            systemReady = false
            Log.w(TAG, "TTS init failed status=$status")
            synchronized(initLock) { initLock.notifyAll() }
            return
        }
        systemReady = true
        try {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) {}
        tts?.setSpeechRate(0.95f)
        tts?.setPitch(1.05f)
        val fa = tts?.setLanguage(Locale("fa", "IR"))
        if (fa == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = Locale.getDefault()
            Log.w(TAG, "FA not supported by current engine, using default")
        } else {
            tts?.language = Locale("fa", "IR")
        }
        Log.i(TAG, "System TTS ready engine=${tts?.defaultEngine}")
        synchronized(initLock) { initLock.notifyAll() }
    }

    private fun awaitReady(timeoutMs: Long = 4000) {
        if (systemReady) return
        synchronized(initLock) {
            if (!systemReady) {
                try { initLock.wait(timeoutMs) } catch (_: InterruptedException) {}
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val clean = sanitizeForSpeech(text)
        if (clean.isEmpty()) {
            onDone?.invoke()
            return
        }
        speaking.set(true)
        Thread {
            try {
                awaitReady()
                val lang = detectLang(clean)
                speakSegment(clean, lang) {}
            } finally {
                speaking.set(false)
                onDone?.invoke()
            }
        }.start()
    }

    fun stop() {
        speaking.set(false)
        try { tts?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }

    private fun speakSegment(text: String, lang: String, onSegmentDone: () -> Unit) {
        val online = NetworkUtils.isOnline(context)
        if (systemReady && tts != null) {
            speakSystem(text, lang, allowOnlineFallback = online, onDone = onSegmentDone)
            return
        }
        if (online) {
            speakOnline(text, lang, onSegmentDone)
            return
        }
        Log.w(TAG, "No TTS available")
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
            else -> { }
        }
        engine.setSpeechRate(0.95f)
        engine.setPitch(1.05f)

        // Request audio focus so voice is audible
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        } catch (_: Exception) {}

        val lock = Object()
        var finished = false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS start $utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                synchronized(lock) { finished = true; lock.notifyAll() }
                onDone()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS error $utteranceId")
                synchronized(lock) { finished = true; lock.notifyAll() }
                if (allowOnlineFallback) speakOnline(text, lang, onDone) else onDone()
            }
        })

        val utteranceId = "aria_${System.currentTimeMillis()}"
        val bundle = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
        Log.d(TAG, "speak result=$result len=${text.length}")
        if (result == TextToSpeech.ERROR) {
            if (allowOnlineFallback) speakOnline(text, lang, onDone) else onDone()
            return
        }
        synchronized(lock) {
            if (!finished) try { lock.wait(90_000) } catch (_: InterruptedException) {}
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
                val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body ?: return@use
                    val tmp = File(context.cacheDir, "aria_tts_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(tmp).use { out -> body.byteStream().copyTo(out) }
                    playFile(tmp)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "online tts failed: ${e.message}")
        } finally {
            onDone()
        }
    }

    private fun playFile(file: File) {
        val lock = Object()
        var done = false
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
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
                if (!done) try { lock.wait(60_000) } catch (_: InterruptedException) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "play failed: ${e.message}")
        } finally {
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    private fun detectLang(text: String): String {
        val faChars = text.count { it in '\u0600'..'\u06FF' }
        return if (faChars >= text.length / 4) "fa" else "en"
    }

    private fun chunkText(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val end = (i + max).coerceAtMost(text.length)
            out.add(text.substring(i, end))
            i = end
        }
        return out
    }

    companion object {
        private const val TAG = "AriaVoiceEngine"
        const val SHERPA_PKG = "com.k2fsa.sherpa.onnx.tts.engine"
        const val SHERPA_ENGINES_URL = "https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html"

        fun sanitizeForSpeech(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                val keep = when {
                    ch.isLetterOrDigit() -> true
                    ch.isWhitespace() -> true
                    ch in '\u0600'..'\u06FF' -> true
                    ch in '\u0750'..'\u077F' -> true
                    ch in '\uFB50'..'\uFDFF' -> true
                    ch in '\uFE70'..'\uFEFF' -> true
                    ch == '\u200c' -> true
                    else -> false
                }
                if (keep) sb.append(ch) else sb.append(' ')
            }
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }

        fun openSystemTtsSettings(context: Context) {
            try {
                context.startActivity(
                    Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                try {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            }
        }

        fun openSherpaEnginesPage(context: Context) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(SHERPA_ENGINES_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }
}
