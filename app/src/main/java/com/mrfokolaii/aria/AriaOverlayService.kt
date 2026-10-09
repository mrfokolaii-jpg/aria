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
import com.mrfokolaii.aria.agent.AgentBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var agentBridge: AgentBridge? = null

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

        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        if (!prefs.getBoolean("floating_widget_enabled", true)) {
            stopSelf()
            return
        }

        val provider = prefs.getString("active_provider", "none") ?: "none"
        val isUno = provider == "unorouter"
        val key = prefs.getString(
            if (isUno) "unorouter_api_key" else "custom_api_key", ""
        )?.trim().orEmpty()
        val endpoint = if (isUno) "https://api.unorouter.com/v1"
            else prefs.getString("custom_api_url", "")?.trim().orEmpty()
        val model = if (isUno) prefs.getString("unorouter_model", "")?.trim().orEmpty()
            else prefs.getString("custom_model", "")?.trim().orEmpty()
        val tested = prefs.getBoolean(
            if (isUno) "unorouter_test_ok" else "custom_test_ok", false
        )

        if (provider != "none" && tested && key.isNotEmpty() &&
            endpoint.isNotBlank() && model.isNotBlank()
        ) {
            agentBridge = AgentBridge(this, key, endpoint, model)
            AriaAccessibilityService.instance?.let { accessibilityService ->
                agentBridge?.setAccessibilityService(accessibilityService)
            }
        }

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
            text.contains("استپ", ignoreCase = true) ||
            text.contains("stop", ignoreCase = true)
        ) {
            tts?.stop()
            showText("⏸")
            return
        }

        if (
            text.contains("خداحافظ") ||
            text.contains("خاموش شو") ||
            text.contains("بسه") ||
            text.contains("تمام")
        ) {
            liveMode = false
            stopListening()
            tts?.stop()
            speak("فعلاً رفیق.")
            showText("●")
            return
        }

        processing = true
        updateNotification("🧠 آریا در حال فکر کردن...")

        serviceScope.launch(Dispatchers.IO) {
            val reply = handleCommand(text)
                ?: processWithAgent(text)

            launch(Dispatchers.Main) {
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
        }
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

    private suspend fun processWithAgent(text: String): String {
        val bridge = agentBridge
        if (bridge != null) {
            try {
                val response = bridge.processWithAgentTools(text, history)
                return response.text
            } catch (e: Exception) {
                android.util.Log.e("AriaService", "AgentBridge error", e)
            }
        }
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        val provider = prefs.getString("active_provider", "none") ?: "none"
        return if (provider == "none") {
            "ابتدا در تنظیمات آریا یکی از اتصال‌ها را تست کن تا فعال شود."
        } else {
            "اتصال فعال نیست یا تست اتصال ناموفق بوده است. تنظیمات API را بررسی کن."
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

        serviceScope.cancel()
        agentBridge = null

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
