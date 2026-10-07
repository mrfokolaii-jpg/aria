package com.mrfokolaii.aria

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var keyInput: EditText

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAria()
        else status("دسترسی میکروفون لازم است.")
    }

    private val phonePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startAria()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            showSetup("ابتدا دسترسی نمایش روی برنامه‌های دیگر را فعال کن.")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        showSetup()
    }

    override fun onResume() {
        super.onResume()

        if (!Settings.canDrawOverlays(this)) return

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!isFinishing) {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }

        if (::keyInput.isInitialized) {
            keyInput.setText(
                getSharedPreferences("aria", MODE_PRIVATE)
                    .getString("gemini_key", "")
            )
        }
    }

    private fun showSetup(message: String = "آریا آماده است.") {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 70, 40, 40)
        }

        val title = TextView(this).apply {
            text = "🤖 ARIA\n\n$message"
            textSize = 24f
        }

        keyInput = EditText(this).apply {
            hint = "Gemini API Key"
            textSize = 16f
            setSingleLine(true)
            inputType = 0x00000081
        }

        val save = Button(this).apply {
            text = "ذخیره و اجرای آریا"
            setOnClickListener {
                val key = keyInput.text.toString().trim()

                if (key.isNotEmpty()) {
                    getSharedPreferences("aria", MODE_PRIVATE)
                        .edit()
                        .putString("gemini_key", key)
                        .apply()
                }

                requestExtraPermissions()
            }
        }

        val info = TextView(this).apply {
            text = "\nکلید فقط روی همین گوشی ذخیره می‌شود و داخل GitHub قرار نمی‌گیرد.\n\nبعد از اجرا، دایره آبی را لمس کن و فارسی صحبت کن."
            textSize = 15f
        }

        root.addView(title)
        root.addView(keyInput)
        root.addView(save)
        root.addView(info)

        setContentView(root)
    }

    private fun requestExtraPermissions() {
        val permissions = mutableListOf<String>()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CALL_PHONE)
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.SEND_SMS)
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (permissions.isNotEmpty()) {
            phonePermission.launch(permissions.toTypedArray())
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
            status("خطای اجرای ARIA: ${e.javaClass.simpleName}")
        }
    }

    private fun status(text: String) {
        showSetup(text)
    }
}
