package com.mrfokolaii.aria

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "ابتدا اجازه نمایش روی برنامه‌های دیگر را فعال کنید",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )

            finish()
            return
        }

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AriaOverlayService::class.java)
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "خطا در راه‌اندازی ARIA: ${e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }

        finish()
    }
}
