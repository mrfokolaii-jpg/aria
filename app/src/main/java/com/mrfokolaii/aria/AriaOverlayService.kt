package com.mrfokolaii.aria

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class AriaOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var orb: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

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

                        MotionEvent.ACTION_UP,
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

    override fun onDestroy() {
        try {
            orb?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }

        orb = null
        params = null
        windowManager = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
