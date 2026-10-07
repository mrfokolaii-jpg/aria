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
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class AriaOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var orb: View? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val notification = Notification.Builder(this, "aria")
            .setContentTitle("ARIA")
            .setContentText("آریا آماده است")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            orb = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(30, 144, 255))
                }

                elevation = 20f

                setOnClickListener {
                    // Voice Agent در مرحله بعد به Orb متصل می‌شود.
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

            windowManager?.addView(orb, params)

        } catch (e: Exception) {
            orb = null
            stopSelf()
        }
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
        try {
            orb?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (_: Exception) {
        }

        orb = null
        windowManager = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
