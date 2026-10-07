package com.mrfokolaii.aria

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

class AriaOverlayService : Service(), TextToSpeech.OnInitListener {

    private var windowManager: WindowManager? = null
    private var orb: View? = null
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var liveMode = false
    private var processing = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<Pair<String, String>>()

    override fun onCreate() {
        super.onCreate()

        createChannel()

        startForeground(
            NOTIFICATION_ID,
            notification("آریا آماده است")
        )

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        tts = TextToSpeech(this, this)
        createOrb()
    }

    private fun createOrb() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val density = resources.displayMetrics.density
            val size = (68 * density).toInt()

            orb = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(30, 144, 255))
                }

                setOnTouchListener { view, event ->
                    val p = params ?: return@setOnTouchListener false

                    when (event.actionMasked) {

                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            startX = p.x
                            startY = p.y
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            p.x = startX - (event.rawX - downX).toInt()
                            p.y = startY + (event.rawY - downY).toInt()

                            try {
                                windowManager?.updateViewLayout(view, p)
                            } catch (_: Exception) {
                            }

                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            val distance = hypot(
                                event.rawX - downX,
                                event.rawY - downY
                            )

                            if (distance < 20f) {
                                toggleLiveMode()
                            }

                            true
                        }

                        MotionEvent.ACTION_CANCEL -> true
                        else -> false
                    }
                }
            }

            params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 180
            }

            windowManager?.addView(orb, params)

        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun toggleLiveMode() {
        liveMode = !liveMode

        if (liveMode) {
            showText("🎤")
            speak("بله رفیق، گوش می‌دم.")
            startListening()
        } else {
            stopListening()
            showText("●")
            speak("باشه.")
        }
    }

    private fun startListening() {
        if (processing) return

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotification("مجوز میکروفون فعال نیست")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("تشخیص گفتار در دسترس نیست")
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        listening = true
                        updateNotification("🎤 گوش می‌دهم — فارسی")
                    }

                    override fun onBeginningOfSpeech() {
                        updateNotification("🎤 در حال شنیدن...")
                    }

                    override fun onRmsChanged(rmsdB: Float) = Unit

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() {
                        listening = false
                    }

                    override fun onError(error: Int) {
                        listening = false

                        if (liveMode && !processing) {
                            android.os.Handler(Looper.getMainLooper())
                                .postDelayed({
                                    if (liveMode && !processing) {
                                        startListening()
                                    }
                                }, 500)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        listening = false

                        val matches =
                            results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                        val text = matches
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        if (text.isNotEmpty()) {
                            showText(text)
                            updateNotification("شنیدم: $text")
                            handleUserText(text)
                        } else if (liveMode) {
                            startListening()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches =
                            partialResults?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                        val text = matches
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        if (text.isNotEmpty()) {
                            showText(text)
                        }
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) = Unit
                }
            )
        }

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "fa-IR"
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                "fa-IR"
            )
            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
            )
            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                3
            )
        }

        try {
            speechRecognizer?.startListening(intent)
            listening = true
        } catch (e: Exception) {
            listening = false
            updateNotification("خطای میکروفون")
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        listening = false
    }

    private fun handleUserText(text: String) {
        if (processing) return

        if (
            text.contains("خداحافظ") ||
            text.contains("خاموش شو") ||
            text.contains("بسه") ||
            text.contains("تمام")
        ) {
            liveMode = false
            stopListening()
            speak("فعلاً رفیق.")
            showText("●")
            return
        }

        processing = true
        updateNotification("🧠 آریا در حال فکر کردن...")

        Thread {
            val reply = handleCommand(text)
                ?: askGemini(text)

            android.os.Handler(Looper.getMainLooper()).post {
                processing = false

                history.add("user" to text)
                history.add("model" to reply)

                if (history.size > 12) {
                    history.subList(
                        0,
                        history.size - 12
                    ).clear()
                }

                showText(reply)
                updateNotification(reply)
                speak(reply)

                if (liveMode) {
                    android.os.Handler(Looper.getMainLooper())
                        .postDelayed({
                            if (liveMode && !processing) {
                                startListening()
                            }
                        }, 700)
                }
            }
        }.start()
    }

    private fun handleCommand(text: String): String? {
        val t = text.trim()

        if (
            t.contains("باتری") ||
            t.contains("شارژ")
        ) {
            val manager =
                getSystemService(BATTERY_SERVICE) as android.os.BatteryManager

            val level = manager.getIntProperty(
                android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
            )

            return "شارژ گوشی الان $level درصده."
        }

        if (
            t.contains("چراغ قوه") ||
            t.contains("فلش")
        ) {
            return "چراغ قوه را از تنظیمات سریع گوشی روشن یا خاموش کن."
        }

        if (
            t.startsWith("تماس") ||
            t.startsWith("زنگ بزن")
        ) {
            updateNotification("برای تماس باید شماره را مشخص کنی.")
            return "شماره‌ای که می‌خواهی باهاش تماس بگیرم را بگو."
        }

        return null
    }

    private fun askGemini(text: String): String {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)

        val key = prefs
            .getString("gemini_key", "")
            ?.trim()
            .orEmpty()

        if (key.isEmpty()) {
            return "رفیق، هنوز کلید Gemini را وارد نکردی. از صفحه ARIA واردش کن."
        }

        val model = "gemini-3.6-flash"

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"

        val root = JSONObject()

        val contents = JSONArray()

        val system = JSONObject()
            .put(
                "parts",
                JSONArray()
                    .put(
                        JSONObject().put(
                            "text",
                            "تو آریا هستی؛ دستیار صوتی فارسی کاربر. " +
                                "صمیمی، کوتاه، دقیق و محاوره‌ای جواب بده."
                        )
                    )
            )

        contents.put(system)

        for ((role, value) in history.takeLast(8)) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                value
                            )
                        )
                    )
            )
        }

        contents.put(
            JSONObject()
                .put(
                    "role",
                    "user"
                )
                .put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            text
                        )
                    )
                )
        )

        root.put("contents", contents)

        return try {
            val body = root
                .toString()
                .toRequestBody(
                    "application/json".toMediaType()
                )

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->

                if (!response.isSuccessful) {
                    return "اتصال به Gemini خطا داد: ${response.code}"
                }

                val raw = response.body
                    ?.string()
                    .orEmpty()

                val json = JSONObject(raw)

                json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "پاسخ خالی از Gemini دریافت شد."
            }

        } catch (e: Exception) {
            "ارتباط با هوش مصنوعی برقرار نشد: ${e.javaClass.simpleName}"
        }
    }

    private fun showText(text: String) {
        android.os.Handler(Looper.getMainLooper()).post {
            if (bubble == null) {
                createBubble()
            }

            bubble?.text = text.take(700)
            bubble?.visibility = View.VISIBLE

            android.os.Handler(Looper.getMainLooper())
                .postDelayed({
                    if (!processing) {
                        bubble?.visibility = View.GONE
                    }
                }, 5000)
        }
    }

    private fun createBubble() {
        try {
            bubble = TextView(this).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(24, 18, 24, 18)

                background = GradientDrawable().apply {
                    cornerRadius = 28f
                    setColor(Color.rgb(35, 35, 42))
                }
            }

            val p = WindowManager.LayoutParams(
                (280 * resources.displayMetrics.density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 100
                y = 260
            }

            windowManager?.addView(bubble, p)

        } catch (_: Exception) {
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true

            val result = tts?.setLanguage(
                Locale("fa", "IR")
            )

            if (
                result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts?.setLanguage(Locale("fa"))
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return

        val clean = text
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .trim()

        if (clean.isEmpty()) return

        tts?.speak(
            clean,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "aria_reply"
        )
    }

    private fun notification(text: String): Notification {
        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("ARIA")
            .setContentText(text.take(120))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager =
            getSystemService(NotificationManager::class.java)

        manager.notify(
            NOTIFICATION_ID,
            notification(text)
        )
    }

    private fun createChannel() {
        val manager =
            getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ARIA Agent",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onDestroy() {
        liveMode = false
        stopListening()

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }

        tts = null

        try {
            orb?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }

        try {
            bubble?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }

        orb = null
        bubble = null
        params = null
        windowManager = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "aria"
        private const val NOTIFICATION_ID = 1001
    }
}
