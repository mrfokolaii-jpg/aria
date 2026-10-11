package com.mrfokolaii.aria

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mrfokolaii.aria.agent.ApiConnectionTester
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
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
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
            text = "ارائه‌دهنده فعال: ${prefs.getString("active_provider", "-")}"
            textSize = 13f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 8, 0, 12)
        }
        root.addView(activeProviderLabel)

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

        // System / Sherpa offline TTS
        root.addView(TextView(this).apply {
            text = "صدای فارسی آفلاین"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 8)
        })
        val sherpaInstalled = try {
            packageManager.getPackageInfo(AriaVoiceEngine.SHERPA_PKG, 0)
            true
        } catch (_: Exception) { false }
        offlineTtsStatus = TextView(this).apply {
            text = if (sherpaInstalled)
                "موتور Sherpa/Piper فارسی نصب است — اپ از آن استفاده می‌کند"
            else
                "موتور Sherpa نصب نیست. APK فارسی Piper را نصب کنید و در تنظیمات TTS گوشی موتور پیش‌فرض کنید."
            textSize = 12f
            setTextColor(if (sherpaInstalled) Color.parseColor("#00E676") else Color.parseColor("#FFAB40"))
            setPadding(0, 0, 0, 8)
        }
        root.addView(offlineTtsStatus)
        root.addView(Button(this).apply {
            text = "تنظیمات صدای گوشی (TTS)"
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener { AriaVoiceEngine.openSystemTtsSettings(this@MainActivity) }
        })
        root.addView(TextView(this).apply {
            text = "اگر صدا درست نیست: تنظیمات گوشی → زبان و ورودی → خروجی گفتار → موتور Sherpa را انتخاب کنید."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 6, 0, 14)
        })

        val groqLayout = collapsibleSection(root, "۱. Groq", "https://console.groq.com/keys")
        groqKey = passField("Groq API Key", prefs.getString("groq_api_key", "") ?: "")
        groqLayout.addView(groqKey)
        groqModel = textField("مدل", prefs.getString("groq_model", "openai/gpt-oss-20b") ?: "openai/gpt-oss-20b")
        groqLayout.addView(groqModel)
        groqLayout.addView(Button(this).apply {
            text = "تست Groq"
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener { testProvider("groq") }
        })
        groqStatus = statusView(prefs.getBoolean("groq_test_ok", false))
        groqLayout.addView(groqStatus)

        val orLayout = collapsibleSection(root, "۲. OpenRouter", "https://openrouter.ai/keys")
        openRouterKey = passField("OpenRouter Key", prefs.getString("openrouter_api_key", "") ?: "")
        orLayout.addView(openRouterKey)
        openRouterModel = textField("مدل", prefs.getString("openrouter_model", "google/gemini-flash-1.5") ?: "google/gemini-flash-1.5")
        orLayout.addView(openRouterModel)
        orLayout.addView(Button(this).apply {
            text = "تست OpenRouter"
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener { testProvider("openrouter") }
        })
        openRouterStatus = statusView(prefs.getBoolean("openrouter_test_ok", false))
        orLayout.addView(openRouterStatus)

        val xaiLayout = collapsibleSection(root, "۳. xAI / Grok", "https://console.x.ai")
        xaiKey = passField("xAI Key", prefs.getString("xai_api_key", "") ?: "")
        xaiLayout.addView(xaiKey)
        xaiModel = textField("مدل", prefs.getString("xai_model", "grok-3") ?: "grok-3")
        xaiLayout.addView(xaiModel)
        xaiLayout.addView(Button(this).apply {
            text = "تست xAI"
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener { testProvider("xai") }
        })
        xaiStatus = statusView(prefs.getBoolean("xai_test_ok", false))
        xaiLayout.addView(xaiStatus)

        val customLayout = collapsibleSection(root, "۴. سفارشی", null)
        customUrl = textField("URL", prefs.getString("custom_api_url", "") ?: "")
        customLayout.addView(customUrl)
        customKey = passField("Key", prefs.getString("custom_api_key", "") ?: "")
        customLayout.addView(customKey)
        customModel = textField("Model", prefs.getString("custom_model", "") ?: "")
        customLayout.addView(customModel)
        customLayout.addView(Button(this).apply {
            text = "تست سفارشی"
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener { testProvider("custom") }
        })
        customStatus = statusView(prefs.getBoolean("custom_test_ok", false))
        customLayout.addView(customStatus)

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
                prefs.edit().putBoolean("floating_widget_enabled", true).apply()
                widgetSwitch.isChecked = true
                ensureRunPermissions()
            }
        })

        setContentView(scroll)
    }

    private fun collapsibleSection(parent: LinearLayout, title: String, regUrl: String?): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.parseColor("#16162A"))
        }
        val header = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor("#90CAF9"))
            setPadding(0, 8, 0, 6)
        }
        box.addView(header)
        if (regUrl != null) {
            box.addView(TextView(this).apply {
                text = regUrl
                textSize = 11f
                setTextColor(Color.parseColor("#64B5F6"))
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(regUrl)))
                }
            })
        }
        parent.addView(box)
        parent.addView(TextView(this).apply { text = " "; textSize = 6f })
        return box
    }

    private fun textField(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setHintTextColor(Color.GRAY)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#252540"))
        setPadding(16, 14, 16, 14)
    }

    private fun passField(hint: String, value: String): EditText = textField(hint, value).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun statusView(ok: Boolean): TextView = TextView(this).apply {
        text = if (ok) "وضعیت: فعال" else "وضعیت: غیرفعال"
        setTextColor(if (ok) Color.parseColor("#00E676") else Color.parseColor("#EF5350"))
        textSize = 12f
        setPadding(0, 4, 0, 8)
    }

    private fun saveFields() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE).edit()
        prefs.putString("groq_api_key", groqKey.text.toString().trim())
        prefs.putString("groq_model", groqModel.text.toString().trim())
        prefs.putString("openrouter_api_key", openRouterKey.text.toString().trim())
        prefs.putString("openrouter_model", openRouterModel.text.toString().trim())
        prefs.putString("xai_api_key", xaiKey.text.toString().trim())
        prefs.putString("xai_model", xaiModel.text.toString().trim())
        prefs.putString("custom_api_url", customUrl.text.toString().trim())
        prefs.putString("custom_api_key", customKey.text.toString().trim())
        prefs.putString("custom_model", customModel.text.toString().trim())
        prefs.apply()
    }

    private fun testProvider(name: String) {
        saveFields()
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        val (key, endpoint, model) = when (name) {
            "openrouter" -> Triple(
                openRouterKey.text.toString().trim(),
                UnoRouterProvider.OPENROUTER_BASE,
                openRouterModel.text.toString().trim()
            )
            "xai" -> Triple(
                xaiKey.text.toString().trim(),
                UnoRouterProvider.XAI_BASE,
                xaiModel.text.toString().trim()
            )
            "custom" -> Triple(
                customKey.text.toString().trim(),
                customUrl.text.toString().trim(),
                customModel.text.toString().trim()
            )
            else -> Triple(
                groqKey.text.toString().trim(),
                UnoRouterProvider.GROQ_BASE,
                groqModel.text.toString().trim()
            )
        }
        Thread {
            val result = ApiConnectionTester.test(key, endpoint, model)
            runOnUiThread {
                val statusView = when (name) {
                    "openrouter" -> openRouterStatus
                    "xai" -> xaiStatus
                    "custom" -> customStatus
                    else -> groqStatus
                }
                statusView.text = if (result.success) "وضعیت: فعال" else "وضعیت: غیرفعال — ${result.message.take(80)}"
                statusView.setTextColor(
                    if (result.success) Color.parseColor("#00E676") else Color.parseColor("#EF5350")
                )
                prefs.edit().putBoolean("${name}_test_ok", result.success).apply()
                if (result.success) {
                    prefs.edit().putString("active_provider", name).apply()
                    activeProviderLabel.text = "ارائه‌دهنده فعال: $name"
                    Toast.makeText(this, "اتصال موفق", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun ensureRunPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "مجوز نمایش روی برنامه‌ها لازم است", Toast.LENGTH_LONG).show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, AriaOverlayService::class.java))
        Toast.makeText(this, "ویجت فعال شد", Toast.LENGTH_SHORT).show()
    }
}
