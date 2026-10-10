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
import kotlinx.coroutines.withContext

class AriaOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isProcessing = false
    private var isHidden = false
    private var preferEnglishStt = false

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var agentBridge: AgentBridge? = null
    private lateinit var chatStore: ChatHistoryStore
    private lateinit var voice: AriaVoiceEngine

    private val inactivityRunnable = Runnable { hideWidget() }

    override fun onCreate() {
        super.onCreate()
        try {
            chatStore = ChatHistoryStore(this)
            voice = AriaVoiceEngine(this).also { it.init() }
            startForegroundNotification()
            initAgentBridge()
            initSpeechRecognizer()
            initFloatingWidget()
            resetInactivityTimer()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            stopSelf()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "aria_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "ARIA ویجت",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "سرویس ویجت شناور"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val showPending = PendingIntent.getService(
            this, 1,
            Intent(this, AriaOverlayService::class.java).apply { action = ACTION_SHOW },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ARIA")
            .setContentText("ویجت صوتی فعال")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(0, "نمایش ویجت", showPending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(1, notification)
    }

    private fun initAgentBridge() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        val provider = prefs.getString("active_provider", "groq") ?: "groq"

        val (apiKey, endpoint, model) = when (provider) {
            "groq" -> Triple(
                prefs.getString("groq_api_key", "") ?: "",
                UnoRouterProvider.GROQ_BASE,
                prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
            )
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
            else -> Triple(
                prefs.getString("custom_api_key", "") ?: "",
                prefs.getString("custom_api_url", "") ?: "",
                prefs.getString("custom_model", "") ?: ""
            )
        }
        agentBridge = AgentBridge(this, apiKey, endpoint, model)
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    updateButton("🎤 گوش می‌دهم...", 0xFFFF5252.toInt())
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    updateButton("⏳ در حال پردازش...", 0xFFFFAB40.toInt())
                }

                override fun onError(error: Int) {
                    isListening = false
                    isProcessing = false
                    if (!preferEnglishStt &&
                        (error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                error == SpeechRecognizer.ERROR_CLIENT ||
                                error == SpeechRecognizer.ERROR_SERVER)
                    ) {
                        preferEnglishStt = true
                        updateButton("🎤 EN...", 0xFFFF5252.toInt())
                        startListeningInternal()
                        return
                    }
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "چیزی نشنیدم، دوباره بگو"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "زمان تمام شد"
                        SpeechRecognizer.ERROR_NETWORK -> "خطای شبکه"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "مجوز میکروفون لازم است"
                        else -> "خطا در تشخیص گفتار ($error)"
                    }
                    updateButton("🤖 ARIA", 0xFF00E676.toInt())
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
                        updateButton("🤖 ARIA", 0xFF00E676.toInt())
                        speak("چیزی نشنیدم")
                        return
                    }
                    Log.d(TAG, "Recognized: $text")
                    processUserInput(text)
                }
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

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        floatButton = Button(this).apply {
            text = "🤖 ARIA"
            setBackgroundColor(0xFF1E1E2F.toInt())
            setTextColor(0xFF00E676.toInt())
            setPadding(28, 18, 28, 18)
            textSize = 15f

            setOnClickListener {
                if (isHidden) {
                    showWidget()
                    return@setOnClickListener
                }
                resetInactivityTimer()
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
                            params.x = initialX + dx
                            params.y = initialY + dy
                            if (!isHidden) {
                                try {
                                    windowManager?.updateViewLayout(floatButton, params)
                                } catch (_: Exception) {
                                }
                            }
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

    private fun hideWidget() {
        if (isHidden || isListening || isProcessing) return
        try {
            floatButton?.let { windowManager?.removeView(it) }
            isHidden = true
        } catch (e: Exception) {
            Log.w(TAG, "hide failed: ${e.message}")
        }
    }

    private fun showWidget() {
        if (!isHidden) {
            resetInactivityTimer()
            return
        }
        try {
            floatButton?.let { btn ->
                overlayParams?.let { windowManager?.addView(btn, it) }
            }
            isHidden = false
            updateButton("🤖 ARIA", 0xFF00E676.toInt())
            resetInactivityTimer()
        } catch (e: Exception) {
            Log.e(TAG, "show failed", e)
            initFloatingWidget()
        }
    }

    private fun startListening() {
        showWidget()
        if (speechRecognizer == null) {
            speak("تشخیص گفتار در دسترس نیست")
            return
        }
        if (isListening || isProcessing) return
        preferEnglishStt = false
        startListeningInternal()
    }

    private fun startListeningInternal() {
        isListening = true
        updateButton(
            if (preferEnglishStt) "🎤 EN..." else "🎤 گوش می‌دهم...",
            0xFFFF5252.toInt()
        )

        val lang = if (preferEnglishStt) "en-US" else "fa-IR"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            updateButton("🤖 ARIA", 0xFF00E676.toInt())
            speak("خطا در شروع تشخیص گفتار")
            Log.e(TAG, "startListening failed", e)
        }
    }

    private fun processUserInput(text: String) {
        isProcessing = true
        updateButton("⏳ فکر می‌کنم...", 0xFFFFAB40.toInt())
        Toast.makeText(this, "شما: $text", Toast.LENGTH_SHORT).show()

        chatStore.append("user", text, source = "voice")

        serviceScope.launch {
            try {
                val bridge = agentBridge
                if (bridge == null) {
                    speak("اتصال به هوش مصنوعی برقرار نیست. تنظیمات را بررسی کنید.")
                    return@launch
                }

                val history = chatStore.asHistoryPairs()

                val response = withContext(Dispatchers.IO) {
                    bridge.processWithAgentTools(text, history)
                }

                chatStore.append("assistant", response.text, source = "voice")
                speak(response.text)
            } catch (e: Exception) {
                Log.e(TAG, "processUserInput failed", e)
                speak("متأسفم، خطایی رخ داد: ${e.localizedMessage}")
            } finally {
                isProcessing = false
                updateButton("🤖 ARIA", 0xFF00E676.toInt())
                resetInactivityTimer()
            }
        }
    }

    private fun updateButton(label: String, color: Int) {
        handler.post {
            floatButton?.text = label
            floatButton?.setTextColor(color)
        }
    }

    private fun speak(text: String) {
        handler.post {
            Toast.makeText(this, text.take(120), Toast.LENGTH_LONG).show()
            voice.speak(text)
            resetInactivityTimer()
        }
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(inactivityRunnable)
        handler.postDelayed(inactivityRunnable, 40_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showWidget()
            ACTION_HIDE -> hideWidget()
            else -> initAgentBridge()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        handler.removeCallbacks(inactivityRunnable)
        speechRecognizer?.destroy()
        try {
            voice.shutdown()
        } catch (_: Exception) {
        }
        if (!isHidden) {
            floatButton?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val TAG = "AriaOverlayService"
        const val ACTION_SHOW = "com.mrfokolaii.aria.SHOW_WIDGET"
        const val ACTION_HIDE = "com.mrfokolaii.aria.HIDE_WIDGET"
    }
}
