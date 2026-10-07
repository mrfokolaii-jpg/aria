package com.mrfokolaii.aria

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAria()
        else show("برای استفاده از صدای ARIA 2، دسترسی میکروفون لازم است.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show("ARIA 2\n\nدر حال آماده‌سازی...")

        if (!Settings.canDrawOverlays(this)) {
            show("ARIA 2\n\nدسترسی نمایش روی برنامه‌های دیگر را فعال کنید.")
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return
        }

        continueStartup()
    }

    override fun onResume() {
        super.onResume()

        if (Settings.canDrawOverlays(this)) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED) {
                startAria()
            } else if (!isFinishing) {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun continueStartup() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startAria()
        }
    }

    private fun startAria() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AriaOverlayService::class.java)
            )
            finish()
        } catch (e: Exception) {
            show("خطای اجرای ARIA 2: ${e.javaClass.simpleName}")
        }
    }

    private fun show(message: String) {
        setContentView(TextView(this).apply {
            text = message
            textSize = 22f
            setPadding(48, 120, 48, 48)
        })
    }
}
