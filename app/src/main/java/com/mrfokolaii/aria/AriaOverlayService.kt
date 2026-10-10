package com.mrfokolaii.aria

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import java.util.Locale

class AriaOverlayService : Service(), TextToSpeech.OnInitListener {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private val inactivityRunnable = Runnable {
        minimizeWidget()
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        initFloatingWidget()
        resetInactivityTimer()
    }

    private fun initFloatingWidget() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
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

        floatView = Button(this).apply {
            text = "🤖 ARIA AI"
            setBackgroundColor(0xFF1E1E2F.toInt())
            setTextColor(0xFF00E676.toInt())
            setPadding(24, 16, 24, 16)
            
            setOnClickListener {
                resetInactivityTimer()
                speak("آماده‌ام، بفرمایید دستور بعدی را بگویید...")
            }
            
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatView, params)
                        true
                    }
                    else -> false
                }
            }
        }

        try {
            windowManager?.addView(floatView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(inactivityRunnable)
        handler.postDelayed(inactivityRunnable, 50000)
    }

    private fun minimizeWidget() {
        (floatView as? Button)?.let {
            it.text = "💤 ARIA"
            it.setTextColor(0xFF888888.toInt())
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        resetInactivityTimer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("fa", "IR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(inactivityRunnable)
        tts?.stop()
        tts?.shutdown()
        if (floatView != null) {
            windowManager?.removeView(floatView)
        }
    }
}
