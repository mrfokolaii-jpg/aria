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
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

class AriaOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var orb: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ARIA")
            .setContentText("آریا آماده است")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        createOrb()
    }

    private fun createOrb() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val size = (68 * resources.displayMetrics.density).toInt()

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
                            windowManager?.updateViewLayout(view, p)
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            val distance = kotlin.math.hypot(
                                event.rawX - downX,
                                event.rawY - downY
                            )

                            if (distance < 20f) {
                                toggleListening()
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
            orb = null
            params = null
            stopSelf()
        }
    }

    private fun toggleListening() {
        if (listening) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
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
            updateNotification("تشخیص گفتار روی این دستگاه در دسترس نیست")
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

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
                        updateNotification("در حال پردازش...")
                    }

                    override fun onError(error: Int) {
                        listening = false
                        updateNotification("خطای تشخیص گفتار: $error")
                    }

                    override fun onResults(results: Bundle?) {
                        listening = false

                        val matches =
                            results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                        val text = matches?.firstOrNull()

                        if (!text.isNullOrBlank()) {
                            updateNotification("شنیدم: $text")
                        } else {
                            updateNotification("چیزی تشخیص داده نشد")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches =
                            partialResults?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                        val text = matches?.firstOrNull()

                        if (!text.isNullOrBlank()) {
                            updateNotification("🎤 $text")
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
            updateNotification("🎤 آماده شنیدن فارسی...")
        } catch (e: Exception) {
            listening = false
            updateNotification(
                "خطا: ${e.javaClass.simpleName}"
            )
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }

        listening = false
        updateNotification("آریا آماده است")
    }

    private fun updateNotification(text: String) {
        val manager =
            getSystemService(NotificationManager::class.java)

        val notification = Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("ARIA")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
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
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null
        listening = false

        try {
            orb?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }

        orb = null
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
