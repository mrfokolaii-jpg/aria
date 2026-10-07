package com.mrfokolaii.aria

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class AriaOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var orb: View? = null

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

                setOnClickListener {
                    // مرحله بعد: اتصال Orb به Agent
                }
            }

            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.TOP or Gravity.END
            params.x = 24
            params.y = 180

            windowManager?.addView(orb, params)

        } catch (_: Exception) {
            orb = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            orb?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }

        orb = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
