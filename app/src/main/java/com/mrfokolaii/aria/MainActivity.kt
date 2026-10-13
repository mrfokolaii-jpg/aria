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
import androidx.core.content.ContextCompat
import com.mrfokolaii.aria.agent.ApiConnectionTester
import com.mrfokolaii.aria.agent.ModelImportUtil
import com.mrfokolaii.aria.agent.UnoRouterProvider
import com.mrfokolaii.aria.memory.PersistentMemory
import com.mrfokolaii.aria.voice.AriaVoiceEngine
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var activeProviderLabel: TextView
    private lateinit var offlineTtsStatus: TextView
    private lateinit var groqKey: EditText
    private lateinit var groqModel: EditText
    private lateinit var groqStatus: TextView
    private lateinit var openRouterKey: EditText
    private lateinit var openRouterModel: EditText
    private lateinit var openRouterStatus: TextView
    private lateinit var xaiKey: EditText
    private lateinit var xaiModel: EditText
    private lateinit var xaiStatus: TextView
    private lateinit var customUrl: EditText
    private lateinit var customKey: EditText
    private lateinit var customModel: EditText
    private lateinit var customStatus: TextView
    private lateinit var widgetSwitch: Switch
    private lateinit var modelStatusView: TextView

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this, "فایلی انتخاب نشد", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        Toast.makeText(this, "در حال کپی مدل... صبر کن (حدود ۱ گیگ)", Toast.LENGTH_LONG).show()
        Thread {
            val result = ModelImportUtil.importFromUri(this, uri)
            runOnUiThread {
                if (result.isSuccess) {
                    val f = result.getOrNull()
                    Toast.makeText(this, "مدل نصب شد (${(f?.length() ?: 0) / 1_000_000} MB)", Toast.LENGTH_LONG).show()
                    if (::modelStatusView.isInitialized) {
                        modelStatusView.text = ModelImportUtil.status(this)
                        modelStatusView.setTextColor(Color.parseColor("#00E676"))
                    }
                } else {
                    Toast.makeText(this, "خطا: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!getSharedPreferences("aria", MODE_PRIVATE).getBoolean("first_run_done", false)) {
            requestAllPermissionsFirstRun()
        }
        showSetup()
    }

    private fun requestAllPermissionsFirstRun() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { perms.add(Manifest.permission.BLUETOOTH_CONNECT) } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1001)
        requestSpecialPermissions()
    }

    private fun requestSpecialPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
        createGrokFolder()
        getSharedPreferences("aria", MODE_PRIVATE).edit().putBoolean("first_run_done", true).apply()
    }

    private fun createGrokFolder() {
        try {
            PersistentMemory(this).saveNote("_welcome", "آماده", listOf("system"))
        } catch (_: Exception) {
            try { File(Environment.getExternalStorageDirectory(), "Grok").mkdirs() } catch (_: Exception) {
                File(filesDir, "Grok").mkdirs()
            }
        }
    }

    private fun showSetup() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 28)
            setBackgroundColor(Color.parseColor("#0F0F1A"))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "تنظیمات ARIA"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 4)
        })

        activeProviderLabel = TextView(this).apply {
            text = "مغز: آفلاین Qwen (بدون API)"
            textSize = 13f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 8, 0, 12)
        }
        root.addView(activeProviderLabel)

        // --- Offline Qwen model ---
        root.addView(TextView(this).apply {
            text = "مدل آفلاین Qwen"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 6)
        })
        modelStatusView = TextView(this).apply {
            text = ModelImportUtil.status(this@MainActivity)
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 0, 0, 8)
        }
        root.addView(modelStatusView)
        root.addView(Button(this).apply {
            text = "انتخاب فایل مدل از Download"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                try {
                    modelPicker.launch(arrayOf("*/*", "application/octet-stream"))
                } catch (_: Exception) {
                    modelPicker.launch(arrayOf("*/*"))
                }
            }
        })
        root.addView(TextView(this).apply {
            text = "فایل qwen2.5-1.5b-instruct-q4_k_m.gguf را از Download انتخاب کن تا داخل اپ کپی شود (حدود ۱ گیگ؛ یک‌بار)."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 12)
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "چت"
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener { startActivity(Intent(this@MainActivity, ChatActivity::class.java)) }
        })
        actions.addView(Button(this).apply {
            text = "مجوزها"
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener { requestAllPermissionsFirstRun() }
        })
        root.addView(actions)

        root.addView(TextView(this).apply {
            text = "ویجت"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        })
        widgetSwitch = Switch(this).apply {
            text = "ویجت شناور روشن/خاموش"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("floating_widget_enabled", true)
            setOnCheckedChangeListener { _, enabled ->
                saveFields()
                prefs.edit().putBoolean("floating_widget_enabled", enabled).apply()
                if (enabled) ensureRunPermissions()
                else stopService(Intent(this@MainActivity, AriaOverlayService::class.java))
            }
        }
        root.addView(widgetSwitch)
        root.addView(Button(this).apply {
            text = "ذخیره و اجرای ویجت"
            setBackgroundColor(Color.parseColor("#00C853"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                saveFields()
                ensureRunPermissions()
                startService(Intent(this@MainActivity, AriaOverlayService::class.java))
                Toast.makeText(this, "ویجت فعال شد", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(TextView(this).apply {
            text = "صدای فارسی / TTS"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        })
        offlineTtsStatus = TextView(this).apply {
            text = try {
                val eng = AriaVoiceEngine(this@MainActivity)
                eng.init()
                "موتور TTS گوشی / Sherpa"
            } catch (_: Exception) { "TTS" }
            textSize = 12f
            setTextColor(Color.parseColor("#80CBC4"))
        }
        root.addView(offlineTtsStatus)
        val ttsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ttsRow.addView(Button(this).apply {
            text = "تنظیمات TTS گوشی"
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        })
        ttsRow.addView(Button(this).apply {
            text = "دانلود صداهای Sherpa"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html")))
            }
        })
        root.addView(ttsRow)

        // Keep optional cloud sections collapsed visually but not required
        root.addView(TextView(this).apply {
            text = "\n(اختیاری — دیگر برای چت لازم نیست)\nکلیدهای ابری"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 16, 0, 8)
        })

        fun collapsible(title: String, url: String): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#16162A"))
                setPadding(16, 12, 16, 12)
            }
            box.addView(TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 14f
            })
            box.addView(TextView(this).apply {
                text = url
                setTextColor(Color.parseColor("#64B5F6"))
                textSize = 11f
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            })
            return box
        }

        val groqBox = collapsible("۱. Groq", "https://console.groq.com/keys")
        groqKey = EditText(this).apply {
            hint = "Groq API Key"
            setText(prefs.getString("groq_api_key", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        groqModel = EditText(this).apply {
            setText(prefs.getString("groq_model", "openai/gpt-oss-20b") ?: "openai/gpt-oss-20b")
            setTextColor(Color.WHITE)
        }
        groqStatus = TextView(this).apply { setTextColor(Color.parseColor("#00E676")); text = "" }
        groqBox.addView(groqKey); groqBox.addView(groqModel); groqBox.addView(groqStatus)
        groqBox.addView(Button(this).apply {
            text = "تست Groq"
            setOnClickListener {
                saveFields()
                Thread {
                    val r = ApiConnectionTester.test(
                        groqKey.text.toString().trim(),
                        UnoRouterProvider.GROQ_BASE,
                        groqModel.text.toString().trim()
                    )
                    runOnUiThread {
                        groqStatus.text = if (r.ok) "وضعیت: فعال" else "خطا: ${r.message}"
                        if (r.ok) prefs.edit().putString("active_provider", "groq").apply()
                    }
                }.start()
            }
        })
        root.addView(groqBox)

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

    private fun passField(hint: String, value: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
}
