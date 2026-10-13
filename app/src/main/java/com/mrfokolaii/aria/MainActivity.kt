package com.mrfokolaii.aria

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.mrfokolaii.aria.agent.ModelImportUtil
import com.mrfokolaii.aria.memory.LocalKnowledgeStore
import com.mrfokolaii.aria.memory.PersistentMemory
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var activeProviderLabel: TextView
    private lateinit var offlineTtsStatus: TextView
    private lateinit var groqKey: EditText
    private lateinit var groqModel: EditText
    private lateinit var groqStatus: TextView
    private lateinit var widgetSwitch: Switch
    private lateinit var modelStatusView: TextView
    private lateinit var knowledgeStatusView: TextView

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this@MainActivity, "فایلی انتخاب نشد", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        Toast.makeText(this@MainActivity, "در حال کپی مدل از حافظه... صبر کن", Toast.LENGTH_LONG).show()
        Thread {
            val result = ModelImportUtil.importFromUri(this@MainActivity, uri)
            runOnUiThread {
                if (result.isSuccess) {
                    val f = result.getOrNull()
                    Toast.makeText(
                        this@MainActivity,
                        "مدل از حافظه نصب شد (${(f?.length() ?: 0) / 1_000_000} MB)",
                        Toast.LENGTH_LONG
                    ).show()
                    if (::modelStatusView.isInitialized) {
                        modelStatusView.text = ModelImportUtil.status(this@MainActivity)
                        modelStatusView.setTextColor(Color.parseColor("#00E676"))
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "خطا: ${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestFirstRunPermissions()
        buildUi()
    }

    private fun requestFirstRunPermissions() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        if (prefs.getBoolean("permissions_requested", false)) return
        prefs.edit().putBoolean("permissions_requested", true).apply()

        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.READ_MEDIA_IMAGES)
            needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            needed.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 28) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun buildUi() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F1A"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 48)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "آریا — تنظیمات"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        })
        activeProviderLabel = TextView(this).apply {
            text = "مغز: MediaPipe GPU + GGUF CPU + دانش"
            textSize = 13f
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding(0, 0, 0, 16)
        }
        root.addView(activeProviderLabel)

        root.addView(TextView(this).apply {
            text = "۱) آپلود مدل از حافظه"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 8)
        })

        modelStatusView = TextView(this).apply {
            text = ModelImportUtil.status(this@MainActivity)
            textSize = 12f
            setTextColor(Color.parseColor("#FFD54F"))
            setPadding(0, 0, 0, 8)
        }
        root.addView(modelStatusView)

        root.addView(Button(this).apply {
            text = "آپلود مدل از حافظه گوشی"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                modelPicker.launch(arrayOf("*/*"))
            }
        })

        root.addView(TextView(this).apply {
            text = "برای GPU: فایل .task (Qwen LiteRT) را آپلود کن. GGUF قبلی هم به‌عنوان CPU کار می‌کند."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 6, 0, 16)
        })

        root.addView(TextView(this).apply {
            text = "۲) دانش محلی (Download/aria pdf)"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 8)
        })

        knowledgeStatusView = TextView(this).apply {
            text = try { LocalKnowledgeStore(this@MainActivity).statusText() } catch (_: Exception) { "—" }
            textSize = 12f
            setTextColor(Color.parseColor("#A5D6A7"))
            setPadding(0, 0, 0, 8)
        }
        root.addView(knowledgeStatusView)

        root.addView(Button(this).apply {
            text = "بروزرسانی دانش"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                try {
                    val store = LocalKnowledgeStore(this@MainActivity)
                    store.ensureFolders()
                    knowledgeStatusView.text = store.statusText()
                    Toast.makeText(this@MainActivity, "دانش بروز شد", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "خطا: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        })

        try {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "aria pdf").mkdirs()
        } catch (_: Exception) {}

        root.addView(TextView(this).apply {
            text = "۳) ویجت شناور"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        })
        widgetSwitch = Switch(this).apply {
            text = "ویجت شناور"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("widget_enabled", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("widget_enabled", checked).apply()
                if (checked) {
                    ensureRunPermissions()
                    startForegroundService(Intent(this@MainActivity, AriaOverlayService::class.java))
                } else {
                    stopService(Intent(this@MainActivity, AriaOverlayService::class.java))
                }
            }
        }
        root.addView(widgetSwitch)

        root.addView(Button(this).apply {
            text = "باز کردن چت"
            setBackgroundColor(Color.parseColor("#6A1B9A"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ChatActivity::class.java))
            }
        })

        root.addView(TextView(this).apply {
            text = "صدای فارسی / TTS"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        })
        offlineTtsStatus = TextView(this).apply {
            text = "موتور TTS گوشی / Sherpa"
            textSize = 12f
            setTextColor(Color.parseColor("#80CBC4"))
        }
        root.addView(offlineTtsStatus)
        val ttsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ttsRow.addView(Button(this).apply {
            text = "تنظیمات TTS"
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        })
        root.addView(ttsRow)

        root.addView(TextView(this).apply {
            text = "\nاختیاری — کلید ابری (چت آفلاین به آن نیاز ندارد)"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 16, 0, 8)
        })
        groqKey = EditText(this).apply {
            hint = "Groq API Key (اختیاری)"
            setText(prefs.getString("groq_api_key", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        groqModel = EditText(this).apply {
            setText(prefs.getString("groq_model", "") ?: "")
            setTextColor(Color.WHITE)
            hint = "model"
            setHintTextColor(Color.GRAY)
        }
        groqStatus = TextView(this).apply { setTextColor(Color.parseColor("#00E676")); text = "" }
        root.addView(groqKey)
        root.addView(groqModel)
        root.addView(groqStatus)

        setContentView(scroll)
    }

    private fun saveFields() {
        val e = getSharedPreferences("aria", MODE_PRIVATE).edit()
        if (::groqKey.isInitialized) e.putString("groq_api_key", groqKey.text.toString().trim())
        if (::groqModel.isInitialized) e.putString("groq_model", groqModel.text.toString().trim())
        e.apply()
    }

    private fun ensureRunPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}
