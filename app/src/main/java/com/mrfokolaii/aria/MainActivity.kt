package com.mrfokolaii.aria

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mrfokolaii.aria.agent.ApiConnectionTester
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var groqKey: EditText
    private lateinit var groqStatus: TextView

    private lateinit var openRouterKey: EditText
    private lateinit var openRouterModel: EditText
    private lateinit var openRouterStatus: TextView

    private lateinit var customUrl: EditText
    private lateinit var customKey: EditText
    private lateinit var customModel: EditText
    private lateinit var customStatus: TextView

    private lateinit var widgetSwitch: Switch

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (micGranted) {
            createAriaFolder()
            startAria()
        } else {
            showStatus("دسترسی میکروفون برای مکالمه صوتی ضروری است.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSetup()
    }

    private fun showSetup() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 32)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "🤖 تنظیمات پیشرفته ARIA (Agent)"
            textSize = 21f
            setPadding(0, 0, 0, 16)
        })

        // --- بخش ۱: Groq ---
        val groqLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 8, 16, 16)
        }
        val groqHeader = TextView(this).apply {
            text = "▼ ۱. Groq (رایگان و پرسرعت)"
            textSize = 16f
            setPadding(0, 12, 0, 6)
            setOnClickListener {
                groqLayout.visibility = if (groqLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        root.addView(groqHeader)

        groqKey = EditText(this).apply {
            hint = "Groq API Key"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("groq_api_key", "") ?: "")
        }
        groqLayout.addView(groqKey)

        Button(this).apply {
            text = "تست اتصال Groq"
            setOnClickListener { testProvider("groq") }
            groqLayout.addView(this)
        }

        groqStatus = TextView(this).apply {
            text = "وضعیت: " + providerStatus(prefs.getBoolean("groq_test_ok", false))
            textSize = 13f
            setPadding(0, 4, 0, 8)
        }
        groqLayout.addView(groqStatus)
        root.addView(groqLayout)

        // --- بخش ۲: OpenRouter ---
        val openRouterLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 8, 16, 16)
        }
        val openRouterHeader = TextView(this).apply {
            text = "▼ ۲. OpenRouter (مدل‌های free)"
            textSize = 16f
            setPadding(0, 12, 0, 6)
            setOnClickListener {
                openRouterLayout.visibility = if (openRouterLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        root.addView(openRouterHeader)

        openRouterKey = EditText(this).apply {
            hint = "OpenRouter API Key"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("openrouter_api_key", "") ?: "")
        }
        openRouterLayout.addView(openRouterKey)

        openRouterModel = EditText(this).apply {
            hint = "نام مدل (مثال: google/gemini-flash-1.5)"
            setSingleLine(true)
            setText(prefs.getString("openrouter_model", "google/gemini-flash-1.5") ?: "")
        }
        openRouterLayout.addView(openRouterModel)

        Button(this).apply {
            text = "تست اتصال OpenRouter"
            setOnClickListener { testProvider("openrouter") }
            openRouterLayout.addView(this)
        }

        openRouterStatus = TextView(this).apply {
            text = "وضعیت: " + providerStatus(prefs.getBoolean("openrouter_test_ok", false))
            textSize = 13f
            setPadding(0, 4, 0, 8)
        }
        openRouterLayout.addView(openRouterStatus)
        root.addView(openRouterLayout)

        // --- بخش ۳: سفارشی / پروکسی دلخواه API ---
        val customLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 8, 16, 16)
        }
        val customHeader = TextView(this).apply {
            text = "▼ ۳. سفارشی / پروکسی دلخواه API"
            textSize = 16f
            setPadding(0, 12, 0, 6)
            setOnClickListener {
                customLayout.visibility = if (customLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        root.addView(customHeader)

        customUrl = EditText(this).apply {
            hint = "آدرس API (Base URL)"
            setSingleLine(true)
            setText(prefs.getString("custom_api_url", "") ?: "")
        }
        customLayout.addView(customUrl)

        customKey = EditText(this).apply {
            hint = "کلید API سفارشی"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("custom_api_key", "") ?: "")
        }
        customLayout.addView(customKey)

        customModel = EditText(this).apply {
            hint = "نام مدل سفارشی"
            setSingleLine(true)
            setText(prefs.getString("custom_model", "") ?: "")
        }
        customLayout.addView(customModel)

        Button(this).apply {
            text = "تست اتصال سفارشی"
            setOnClickListener { testProvider("custom") }
            customLayout.addView(this)
        }

        customStatus = TextView(this).apply {
            text = "وضعیت: " + providerStatus(prefs.getBoolean("custom_test_ok", false))
            textSize = 13f
            setPadding(0, 4, 0, 8)
        }
        customLayout.addView(customStatus)
        root.addView(customLayout)

        // --- تنظیمات ویجت شناور ---
        root.addView(TextView(this).apply {
            text = "⚙️ تنظیمات ویجت شناور و حافظه"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })

        widgetSwitch = Switch(this).apply {
            text = "روشن / خاموش بودن ویجت شناور"
            isChecked = prefs.getBoolean("floating_widget_enabled", true)
            setOnCheckedChangeListener { _, enabled ->
                saveFields()
                getSharedPreferences("aria", MODE_PRIVATE).edit()
                    .putBoolean("floating_widget_enabled", enabled)
                    .apply()
                if (enabled) ensureRunPermissions()
                else {
                    stopService(Intent(this@MainActivity, AriaOverlayService::class.java))
                    showStatus("ویجت شناور خاموش شد.")
                }
            }
        }
        root.addView(widgetSwitch)

        Button(this).apply {
            text = "ذخیره و اجرای ویجت شناور و ساخت پوشه ARIA"
            setOnClickListener {
                saveFields()
                getSharedPreferences("aria", MODE_PRIVATE).edit()
                    .putBoolean("floating_widget_enabled", true)
                    .apply()
                widgetSwitch.isChecked = true
                ensureRunPermissions()
            }
            root.addView(this)
        }

        setContentView(scroll)
    }

    private fun providerStatus(ok: Boolean) =
        if (ok) "موفق ✅" else "غیرفعال یا تست‌نشده ❌"

    private fun saveFields() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE).edit()
        prefs.putString("groq_api_key", groqKey.text.toString().trim())
            .putString("openrouter_api_key", openRouterKey.text.toString().trim())
            .putString("openrouter_model", openRouterModel.text.toString().trim())
            .putString("custom_api_url", customUrl.text.toString().trim())
            .putString("custom_api_key", customKey.text.toString().trim())
            .putString("custom_model", customModel.text.toString().trim())
            .apply()
    }

    private fun testProvider(which: String) {
        saveFields()
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        val (key, url, model) = when (which) {
            "groq" -> arrayOf(groqKey.text.toString().trim(), "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile")
            "openrouter" -> arrayOf(openRouterKey.text.toString().trim(), "https://openrouter.ai/api/v1", openRouterModel.text.toString().trim())
            else -> arrayOf(customKey.text.toString().trim(), customUrl.text.toString().trim(), customModel.text.toString().trim())
        }

        showStatus("در حال تست اتصال $which...")
        Thread {
            val result = ApiConnectionTester.test(key, url, model)
            runOnUiThread {
                prefs.edit().putBoolean("${which}_test_ok", result.success).apply()
                if (result.success) {
                    prefs.edit().putString("active_provider", which).apply()
                }
                when (which) {
                    "groq" -> groqStatus.text = "وضعیت: " + providerStatus(result.success)
                    "openrouter" -> openRouterStatus.text = "وضعیت: " + providerStatus(result.success)
                    else -> customStatus.text = "وضعیت: " + providerStatus(result.success)
                }
                showStatus("$which: ${result.message}")
            }
        }.start()
    }

    private fun ensureRunPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            showStatus("لطفاً مجوز نمایش روی سایر برنامه‌ها را فعال کنید.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val needed = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionsLauncher.launch(needed.toTypedArray())
            return
        }

        createAriaFolder()
        startAria()
    }

    private fun createAriaFolder() {
        try {
            val dir = File(Environment.getExternalStorageDirectory(), "ARIA")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val readme = File(dir, "knowledge_base.txt")
            if (!readme.exists()) {
                readme.writeText("=== ARIA Local Knowledge Base & Notes ===\n")
            }
        } catch (e: Exception) {
            val internalDir = File(filesDir, "ARIA")
            if (!internalDir.exists()) internalDir.mkdirs()
        }
    }

    private fun startAria() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, AriaOverlayService::class.java))
            showStatus("ویجت شناور فعال و پوشه ARIA آماده شد.")
        } catch (e: Exception) {
            showStatus("خطا در اجرا: ${e.message}")
        }
    }

    private fun showStatus(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
