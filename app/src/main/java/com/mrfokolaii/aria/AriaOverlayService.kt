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
    private var conversationActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val idleTimeout = Runnable {
        conversationActive = false
        updateButton("ARIA", 0xFF00C853.toInt())
        updateStatus("ویجت صوتی فعال")
        Log.d(TAG, "Conversation idle — deactivated")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        chatStore = ChatHistoryStore(this)
        voiceEngine = AriaVoiceEngine(this).also { it.init() }
        initBridge()
        startForeground(NOTIFICATION_ID, buildNotification("ویجت صوتی فعال"))
        initSpeech()
        initFloatingWidget()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showWidget()
        }
        if (floatButton == null || isHidden) {
            initFloatingWidget()
        }
        return START_STICKY
    }

    private fun initBridge() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        fun load(provider: String): Triple<String, String, String> = when (provider) {
            "openrouter" -> Triple(
                prefs.getString("openrouter_api_key", "") ?: "",
                UnoRouterProvider.OPENROUTER_BASE,
                prefs.getString("openrouter_model", "openrouter/free") ?: "openrouter/free"
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
        var provider = prefs.getString("active_provider", "groq") ?: "groq"
        var (key, endpoint, model) = load(provider)
        if (key.isBlank()) {
            for (p in listOf("groq", "openrouter", "xai", "custom")) {
                val t = load(p)
                if (t.first.isNotBlank()) {
                    provider = p
                    key = t.first
                    endpoint = t.second
                    model = t.third
                    prefs.edit().putString("active_provider", p).apply()
                    break
                }
            }
        }
        agentBridge = AgentBridge(this, key, endpoint, model)
    }

    private fun processUserInput(text: String) {
        initBridge()
        isProcessing = true
        conversationActive = true
        armIdleTimer()
        updateButton("...", 0xFF2196F3.toInt())
        updateStatus("⏳ در حال آماده‌سازی…")
        chatStore?.append("user", text, "voice")
        scope.launch {
            try {
                val history = chatStore?.loadAll()?.map { it.role to it.text }?.takeLast(12) ?: emptyList()
                val response = agentBridge?.processWithAgentTools(text, history) { status ->
                    handler.post {
                        updateStatus(status)
                        updateButton(status.take(10), 0xFF2196F3.toInt())
                    }
                }
                val reply = response?.text ?: "پاسخی دریافت نشد"
                chatStore?.append("assistant", reply, "voice")
                updateButton("ARIA", 0xFF00C853.toInt())
                updateStatus("در حال پخش پاسخ…")
                speak(reply) {
                    armIdleTimer()
                    updateStatus("منتظر دستور بعدی (۳۰ ثانیه)")
                    updateButton("آماده", 0xFFFF9800.toInt())
                    scheduleListenAgain()
                }
            } catch (e: Exception) {
                Log.e(TAG, "process", e)
                updateButton("ARIA", 0xFF00C853.toInt())
                updateStatus("ویجت صوتی فعال")
                speak("خطا در پردازش") {
                    if (conversationActive) scheduleListenAgain()
                }
            } finally {
                isProcessing = false
            }
        }
    }

    private fun speak(msg: String, onDone: (() -> Unit)? = null) {
        voiceEngine?.speak(msg, onDone)
    }

    private fun armIdleTimer() {
        handler.removeCallbacks(idleTimeout)
        handler.postDelayed(idleTimeout, 30_000L)
    }

    private fun scheduleListenAgain() {
        handler.postDelayed({
            if (conversationActive && !isProcessing) startListening()
        }, 600)
    }

    private fun updateStatus(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun updateButton(label: String, color: Int) {
        handler.post {
            floatButton?.text = label
            floatButton?.setBackgroundColor(color)
        }
    }

    private fun buildNotification(content: String): Notification {
        val channelId = "aria_voice"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Aria Voice", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("آریا")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun initSpeech() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private fun startListening() {
        val sr = speechRecognizer ?: return
        if (isListening || isProcessing) return
        isListening = true
        updateButton("🎤", 0xFFE91E63.toInt())
        updateStatus("در حال شنیدن…")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (preferEnglishStt) Locale.US.toString() else "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                updateButton("ARIA", 0xFF00C853.toInt())
                if (conversationActive) scheduleListenAgain()
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) processUserInput(text)
                else if (conversationActive) scheduleListenAgain()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            isListening = false; isProcessing = false
            updateButton("ARIA", 0xFF00C853.toInt())
            updateStatus("ویجت صوتی فعال")
            Log.e(TAG, "startListening", e)
        }
    }

    private fun initFloatingWidget() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "مجوز نمایش روی برنامه‌ها لازم است", Toast.LENGTH_LONG).show()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatButton = Button(this).apply {
            text = "ARIA"
            setBackgroundColor(0xFF00C853.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                if (isProcessing) return@setOnClickListener
                conversationActive = true
                armIdleTimer()
                startListening()
            }
            setOnTouchListener(object : android.view.View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                override fun onTouch(v: android.view.View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = overlayParams?.x ?: 0
                            initialY = overlayParams?.y ?: 0
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            overlayParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                            overlayParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(floatButton, overlayParams)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            val dx = kotlin.math.abs(event.rawX - initialTouchX)
                            val dy = kotlin.math.abs(event.rawY - initialTouchY)
                            if (dx < 10 && dy < 10) v?.performClick()
                            return true
                        }
                    }
                    return false
                }
            })
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }
        try {
            windowManager?.addView(floatButton, overlayParams)
            isHidden = false
        } catch (e: Exception) {
            Log.e(TAG, "addView", e)
        }
    }

    private fun showWidget() {
        if (floatButton == null) initFloatingWidget()
        isHidden = false
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { windowManager?.removeView(floatButton) } catch (_: Exception) {}
        speechRecognizer?.destroy()
        voiceEngine?.shutdown()
        scope.cancel()
    }

    companion object {
        private const val TAG = "AriaOverlay"
        private const val NOTIFICATION_ID = 42
        const val ACTION_SHOW = "com.mrfokolaii.aria.SHOW_WIDGET"
    }
}
