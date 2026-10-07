package com.mrfokolaii.aria

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class AriaOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var orb: View

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val notification = Notification.Builder(this, "aria")
            .setContentTitle("ARIA")
            .setContentText("آریا آماده است")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1001, notification)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        orb = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(30, 144, 255))
            }

            elevation = 20f

            setOnClickListener {
                // Voice Agent در مرحله بعد به این Orb متصل می‌شود.
            }
        }

        val size = (68 * resources.displayMetrics.density).toInt()

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

        windowManager.addView(orb, params)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                "aria",
                "ARIA Agent",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onDestroy() {
        if (::orb.isInitialized) {
            windowManager.removeView(orb)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
