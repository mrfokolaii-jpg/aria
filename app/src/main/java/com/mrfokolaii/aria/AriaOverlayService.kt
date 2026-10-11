package com.mrfokolaii.aria

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mrfokolaii.aria.agent.AgentBridge
import com.mrfokolaii.aria.agent.UnoRouterProvider
import com.mrfokolaii.aria.memory.ChatHistoryStore
import com.mrfokolaii.aria.voice.AriaVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class AriaOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceEngine: AriaVoiceEngine? = null
    private var agentBridge: AgentBridge? = null
    private var chatStore: ChatHistoryStore? = null

    private var isListening = false
    private var isProcessing = false
    private var isHidden = false
    private var preferEnglishStt = false

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Do not auto-hide quickly — user reported widget disappearing
    private val inactivityRunnable = Runnable { /* keep visible */ }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        chatStore = ChatHistoryStore(this)
        voiceEngine = AriaVoiceEngine(this).also { it.init() }
        initBridge()
        startForeground(NOTIFICATION_ID, buildNotification())
        initSpeech()
        initFloatingWidget()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showWidget()
            ACTION_HIDE -> { /* ignore hide from timer */ }
        }
        if (floatButton == null || isHidden) {
            initFloatingWidget()
        }
        return START_STICKY
    }

    private fun initBridge() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        val provider = prefs.getString("active_provider", "groq") ?: "groq"
        val (key, endpoint, model) = when (provider) {
            "openrouter" -> Triple(
                prefs.getString("openrouter_api_key", "") ?: "",
                UnoRouterProvider.OPENROUTER_BASE,
                prefs.getString("openrouter_model", "google/gemini-flash-1.5") ?: "google/gemini-flash-1.5"
            )
            "xai" -> Triple(
                prefs.getString("xai_api_key", "") ?: "",
                UnoRouterProvider.XAI_BASE,
                prefs.getString("xai_model", "grok-3") ?: "grok-3"
            )
            "custom" -> Triple(
                prefs.getString("custom_api_key", "") ?: "",
                prefs.getString("custom_api_url", "") ?: "",
                prefs.getString("custom_model", "") ?: ""
            )
            else -> Triple(
                prefs.getString("groq_api_key", "") ?: "",
                UnoRouterProvider.GROQ_BASE,
                prefs.getString("groq_model", "openai/gpt-oss-20b") ?: "openai/gpt-oss-20b"
            )
        }
        agentBridge = AgentBridge(this, key, endpoint, model)
    }

    private fun buildNotification(): Notification {
        val channelId = "aria_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "ARIA ویجت", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val showPi = PendingIntent.getService(
            this, 1,
            Intent(this, AriaOverlayService::class.java).apply { action = ACTION_SHOW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ARIA")
            .setContentText("ویجت صوتی فعال — ضربه برای نمایش")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(0, "نمایش ویجت", showPi)
            .setOngoing(true)
            .build()
    }

    private fun initSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    updateButton("بگو...", 0xFFFFC107.toInt())
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    updateButton("صبر کن...", 0xFF2196F3.toInt())
                }
                override fun onError(error: Int) {
                    isListening = false
                    isProcessing = false
                    updateButton("ARIA", 0xFF00C853.toInt())
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "چیزی نشنیدم"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "زمان تمام شد"
                        else -> "خطای تشخیص صدا"
                    }
                    speak(msg)
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    preferEnglishStt = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim()
                    if (text.isNullOrBlank()) {
                        isProcessing = false
                        updateButton("ARIA", 0xFF00C853.toInt())
                        speak("چیزی نشنیدم")
                        return
                    }
                    Log.d(TAG, "Recognized: $text")
                    processUserInput(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun initFloatingWidget() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "مجوز «نمایش روی برنامه‌ها» را بدهید تا ویجت دیده شود",
                Toast.LENGTH_LONG
            ).show()
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted")
            return
        }

        if (windowManager == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }

        // Remove old view if any
        try { floatButton?.let { windowManager?.removeView(it) } } catch (_: Exception) {}

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 220
        }

        floatButton = Button(this).apply {
            text = "ARIA"
            setBackgroundColor(0xEE00C853.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(36, 28, 36, 28)
            textSize = 16f
            elevation = 12f

            setOnClickListener {
                if (isHidden) {
                    showWidget()
                    return@setOnClickListener
                }
                if (isListening || isProcessing) return@setOnClickListener
                startListening()
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var moved = false

            setOnTouchListener { _, event ->
                val params = overlayParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                            moved = true
                            params.x = initialX - dx
                            params.y = initialY + dy
                            try {
                                windowManager?.updateViewLayout(floatButton, params)
                            } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) performClick()
                        true
                    }
                    else -> false
                }
            }
        }

        try {
            windowManager?.addView(floatButton, overlayParams)
            isHidden = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
            Toast.makeText(this, "نمایش ویجت ممکن نشد: مجوز را چک کنید", Toast.LENGTH_LONG).show()
        }
    }

    private fun showWidget() {
        if (!isHidden && floatButton != null) return
        try {
            if (floatButton == null) {
                initFloatingWidget()
            } else {
                overlayParams?.let { windowManager?.addView(floatButton, it) }
                isHidden = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "show failed: ${e.message}")
            initFloatingWidget()
        }
    }

    private fun updateButton(label: String, color: Int) {
        handler.post {
            floatButton?.text = label
            floatButton?.setBackgroundColor(color)
        }
    }

    private fun startListening() {
        if (isListening || isProcessing) return
        val sr = speechRecognizer ?: run {
            speak("تشخیص گفتار در دسترس نیست")
            return
        }
        isListening = true
        isProcessing = true
        updateButton("بگو...", 0xFFFFC107.toInt())
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                if (preferEnglishStt) Locale.US.toString() else "fa-IR"
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            isProcessing = false
            updateButton("ARIA", 0xFF00C853.toInt())
            Log.e(TAG, "startListening", e)
        }
    }

    private fun processUserInput(text: String) {
        isProcessing = true
        updateButton("...", 0xFF2196F3.toInt())
        chatStore?.append("user", text, "voice")
        scope.launch {
            try {
                val history = chatStore?.loadAll()?.map { it.role to it.text }?.takeLast(12) ?: emptyList()
                val response = agentBridge?.processWithAgentTools(text, history)
                val reply = response?.text ?: "پاسخی دریافت نشد"
                chatStore?.append("assistant", reply, "voice")
                updateButton("ARIA", 0xFF00C853.toInt())
                speak(reply)
            } catch (e: Exception) {
                Log.e(TAG, "process", e)
                updateButton("ARIA", 0xFF00C853.toInt())
                speak("خطا در پردازش")
            } finally {
                isProcessing = false
            }
        }
    }

    private fun speak(msg: String) {
        voiceEngine?.speak(msg)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { floatButton?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        voiceEngine?.shutdown()
        floatButton = null
        isHidden = true
    }

    companion object {
        private const val TAG = "AriaOverlayService"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_SHOW = "com.mrfokolaii.aria.SHOW_WIDGET"
        const val ACTION_HIDE = "com.mrfokolaii.aria.HIDE_WIDGET"
    }
}
