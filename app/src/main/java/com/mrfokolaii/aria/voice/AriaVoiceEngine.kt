package com.mrfokolaii.aria.voice

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
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
 * Uses the **phone default TTS engine** (whatever user picks in system settings).
 * Not bound to a fixed package name — change voice in phone settings and app follows.
 *
 * Priority: System TTS → Online Google TTS (if online).
 */
class AriaVoiceEngine(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var systemReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val speaking = AtomicBoolean(false)
    private val offlineFa = OfflinePersianTts(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun init() {
        // Always default system engine — user can switch voice in phone TTS settings
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
            return
        }
        systemReady = true
        tts?.setSpeechRate(0.95f)
        tts?.setPitch(1.05f)
        // Prefer Persian if engine supports it; otherwise keep default
        val fa = tts?.setLanguage(Locale("fa", "IR"))
        if (fa == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = Locale.getDefault()
        } else {
            tts?.language = Locale("fa", "IR")
        }
        Log.i(TAG, "System TTS ready defaultEngine=${tts?.defaultEngine}")
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
        if (systemReady) {
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
            else -> { /* keep current */ }
        }
        engine.setSpeechRate(0.95f)
        engine.setPitch(1.05f)

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
        @Suppress("DEPRECATION")
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

        /**
         * Remove symbols/punctuation so TTS does not say "star", "parenthesis", etc.
         * Keeps Persian/Arabic letters, Latin letters, digits, spaces, and basic Persian punctuation.
         */
        fun sanitizeForSpeech(raw: String): String {
            // Strip markdown-ish and decorative symbols; keep readable text
            var s = raw
            // Common emoji / stars / bullets
            s = s.replace(Regex("[*⭐✦✧✩✪✫✬✭✮✯✰∗﹡＊•●○◆◇■□▪▫►◄▶◀✓✔︎✅❌➕➖✖➗="']+"), " ")
            // Math & currency & special
            s = s.replace(Regex("[+\\-*/()\\[\\]{}<>|\\\\^~`@#_$%&=±×÷√π∆§™®©₤¥¢€£°_]+"), " ")
            // Persian/Arabic decorative and punctuation that sounds bad when read aloud
            s = s.replace(Regex("[«»״׳٪٫٬،؛؟…!!:;,./]+"), " ")
            // Zero-width and control
            s = s.replace(Regex("[\\u200c\\u200f\\u202a-\\u202e\\u2060\\ufeff]+"), " ")
            // Collapse whitespace
            s = s.replace(Regex("\\s+"), " ").trim()
            return s
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
