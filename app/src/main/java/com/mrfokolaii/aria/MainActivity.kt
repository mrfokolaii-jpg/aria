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
import android.text.InputType
import android.view.View
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
import com.mrfokolaii.aria.agent.UnoRouterProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    // Groq
    private lateinit var groqKey: EditText
    private lateinit var groqModel: EditText
    private lateinit var groqStatus: TextView

    // OpenRouter
    private lateinit var openRouterKey: EditText
    private lateinit var openRouterModel: EditText
    private lateinit var openRouterStatus: TextView

    // xAI / Grok
    private lateinit var xaiKey: EditText
    private lateinit var xaiModel: EditText
    private lateinit var xaiStatus: TextView

    // Custom
    private lateinit var customUrl: EditText
    private lateinit var customKey: EditText
    private lateinit var customModel: EditText
    private lateinit var customStatus: TextView

    private lateinit var widgetSwitch: Switch
    private lateinit var activeProviderLabel: TextView

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
            setBackgroundColor(Color.parseColor("#121212"))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)

        // Title
        root.addView(TextView(this).apply {
            text = "🤖 تنظیمات ARIA"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        })

        activeProviderLabel = TextView(this).apply {
            val active = prefs.getString("active_provider", "-") ?: "-"
            text = "ارائه‌دهنده فعال: $active"
            textSize = 13f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 0, 0, 16)
        }
        root.addView(activeProviderLabel)

        // ===== 1. Groq =====
        val groqLayout = collapsibleSection(
            root,
            "۱. Groq (رایگان و پرسرعت)",
            "https://console.groq.com/keys"
        )

        groqKey = EditText(this).apply {
            hint = "Groq API Key"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("groq_api_key", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        groqLayout.addView(groqKey)

        groqModel = EditText(this).apply {
            hint = "مدل (مثال: llama-3.3-70b-versatile)"
            setSingleLine(true)
            setText(prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        groqLayout.addView(groqModel)

        addModelHints(groqLayout, UnoRouterProvider.GROQ_DEFAULT_MODELS)

        Button(this).apply {
            text = "تست و فعال‌سازی Groq"
            setOnClickListener { testProvider("groq") }
            groqLayout.addView(this)
        }

        groqStatus = TextView(this).apply {
            text = "وضعیت: " + providerStatus(prefs.getBoolean("groq_test_ok", false))
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 8)
        }
        groqLayout.addView(groqStatus)

        // ===== 2. OpenRouter =====
        val orLayout = collapsibleSection(
            root,
            "۲. OpenRouter (مدل‌های رایگان)",
            "https://openrouter.ai/keys"
        )

        openRouterKey = EditText(this).apply {
            hint = "OpenRouter API Key"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("openrouter_api_key", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        orLayout.addView(openRouterKey)

        openRouterModel = EditText(this).apply {
            hint = "نام مدل (مثال: google/gemini-flash-1.5)"
            setSingleLine(true)
            setText(prefs.getString("openrouter_model", "google/gemini-flash-1.5") ?: "google/gemini-flash-1.5")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        orLayout.addView(openRouterModel)

        Button(this).apply {
            text = "تست و فعال‌سازی OpenRouter"
            setOnClickListener { testProvider("openrouter") }
            orLayout.addView(this)
        }

        openRouterStatus = TextView(this).apply {
            text = "وضعیت: " + providerStatus(prefs.getBoolean("openrouter_test_ok", false))
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 8)
        }
        orLayout.addView(openRouterStatus)

        // ===== 3. xAI / Grok =====
        val xaiLayout = collapsibleSection(
            root,
            "۳. xAI / Grok (از xAI)",
            "https://console.x.ai"
        )

        xaiKey = EditText(this).apply {
            hint = "xAI API Key"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("xai_api_key", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        xaiLayout.addView(xaiKey)

        xaiModel = EditText(this).apply {
            hint = "مدل (مثال: grok-3)"
            setSingleLine(true)
            setText(prefs.getString("xai_model", "grok-3") ?: "grok-3")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        xaiLayout.addView(xaiModel)

        addModelHints(xaiLayout, UnoRouterProvider.XAI_DEFAULT_MODELS)

        Button(this).apply {
            text = "تست و فعال‌سازی xAI / Grok"
            setOnClickListener { testProvider("xai") }
            xaiLayout.addView(this)
        }

        xaiStatus = TextView(this).apply {
            text = "وضعیت: " + providerStatus(prefs.getBoolean("xai_test_ok", false))
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 8)
        }
        xaiLayout.addView(xaiStatus)

        // ===== 4. Custom =====
        val customLayout = collapsibleSection(root, "۴. سفارشی / پروکسی API", null)

        customUrl = EditText(this).apply {
            hint = "آدرس API (Base URL)"
            setSingleLine(true)
            setText(prefs.getString("custom_api_url", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        customLayout.addView(customUrl)

        customKey = EditText(this).apply {
            hint = "کلید API سفارشی"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("custom_api_key", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        customLayout.addView(customKey)

        customModel = EditText(this).apply {
            hint = "نام مدل سفارشی"
            setSingleLine(true)
            setText(prefs.getString("custom_model", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        customLayout.addView(customModel)

        Button(this).apply {
            text = "تست و فعال‌سازی سفارشی"
            setOnClickListener { testProvider("custom") }
            customLayout.addView(this)
        }

        customStatus = TextView(this).apply {
            text = "وضعیت: " + providerStatus(prefs.getBoolean("custom_test_ok", false))
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 8)
        }
        customLayout.addView(customStatus)

        // ===== Widget settings =====
        root.addView(TextView(this).apply {
            text = "⚙️ ویجت شناور"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 8)
        })

        widgetSwitch = Switch(this).apply {
            text = "روشن / خاموش بودن ویجت شناور"
            setTextColor(Color.WHITE)
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
            text = "💾 ذخیره و اجرای ویجت"
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

        root.addView(TextView(this).apply {
            text = "💡 پس از فعال‌سازی، روی ویجت ضربه بزنید و صحبت کنید."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 16, 0, 0)
        })

        setContentView(scroll)
    }

    private fun collapsibleSection(
        parent: LinearLayout,
        title: String,
        regUrl: String?
    ): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 8, 16, 16)
        }

        val header = TextView(this).apply {
            text = "▼ $title"
            textSize = 15f
            setTextColor(Color.parseColor("#90CAF9"))
            setPadding(0, 14, 0, 6)
            setOnClickListener {
                section.visibility =
                    if (section.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        parent.addView(header)

        if (regUrl != null) {
            section.addView(TextView(this).apply {
                text = "🔑 دریافت کلید: $regUrl"
                textSize = 12f
                setTextColor(Color.parseColor("#64B5F6"))
                setPadding(0, 0, 0, 6)
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(regUrl)))
                }
            })
        }

        parent.addView(section)
        return section
    }

    private fun addModelHints(parent: LinearLayout, models: List<String>) {
        parent.addView(TextView(this).apply {
            text = "مدل‌های پیشنهادی: ${models.joinToString(" | ")}"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 2, 0, 6)
        })
    }

    private fun providerStatus(ok: Boolean) =
        if (ok) "موفق ✅" else "غیرفعال یا تست‌نشده ❌"

    private fun saveFields() {
        getSharedPreferences("aria", MODE_PRIVATE).edit()
            .putString("groq_api_key", groqKey.text.toString().trim())
            .putString("groq_model", groqModel.text.toString().trim())
            .putString("openrouter_api_key", openRouterKey.text.toString().trim())
            .putString("openrouter_model", openRouterModel.text.toString().trim())
            .putString("xai_api_key", xaiKey.text.toString().trim())
            .putString("xai_model", xaiModel.text.toString().trim())
            .putString("custom_api_url", customUrl.text.toString().trim())
            .putString("custom_api_key", customKey.text.toString().trim())
            .putString("custom_model", customModel.text.toString().trim())
            .apply()
    }

    private fun testProvider(which: String) {
        saveFields()
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)

        val (key, url, model) = when (which) {
            "groq" -> Triple(
                groqKey.text.toString().trim(),
                UnoRouterProvider.GROQ_BASE,
                groqModel.text.toString().trim().ifBlank { "llama-3.3-70b-versatile" }
            )
            "openrouter" -> Triple(
                openRouterKey.text.toString().trim(),
                UnoRouterProvider.OPENROUTER_BASE,
                openRouterModel.text.toString().trim()
            )
            "xai" -> Triple(
                xaiKey.text.toString().trim(),
                UnoRouterProvider.XAI_BASE,
                xaiModel.text.toString().trim().ifBlank { "grok-3" }
            )
            else -> Triple(
                customKey.text.toString().trim(),
                customUrl.text.toString().trim(),
                customModel.text.toString().trim()
            )
        }

        showStatus("در حال تست اتصال $which...")
        Thread {
            val result = ApiConnectionTester.test(key, url, model)
            runOnUiThread {
                prefs.edit().putBoolean("${which}_test_ok", result.success).apply()
                if (result.success) {
                    prefs.edit().putString("active_provider", which).apply()
                    activeProviderLabel.text = "ارائه‌دهنده فعال: $which"
                }
                val statusText = "وضعیت: " + providerStatus(result.success)
                when (which) {
                    "groq" -> groqStatus.text = statusText
                    "openrouter" -> openRouterStatus.text = statusText
                    "xai" -> xaiStatus.text = statusText
                    else -> customStatus.text = statusText
                }
                showStatus("$which: ${result.message}")
            }
        }.start()
    }

    private fun ensureRunPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            showStatus("لطفاً مجوز نمایش روی سایر برنامه‌ها را فعال کنید.")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
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
            if (!dir.exists()) dir.mkdirs()
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
            ContextCompat.startForegroundService(
                this,
                Intent(this, AriaOverlayService::class.java)
            )
            showStatus("ویجت شناور فعال شد. روی آن ضربه بزنید و صحبت کنید.")
        } catch (e: Exception) {
            showStatus("خطا در اجرا: ${e.message}")
        }
    }

    private fun showStatus(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
