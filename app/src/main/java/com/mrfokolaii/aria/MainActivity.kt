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
import com.mrfokolaii.aria.memory.PersistentMemory
import java.io.File

class MainActivity : AppCompatActivity() {

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
    private lateinit var activeProviderLabel: TextView

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allOk = results.values.all { it }
        if (allOk || (results[Manifest.permission.RECORD_AUDIO] == true)) {
            requestSpecialPermissions()
        } else {
            showStatus("برخی مجوزها رد شدند. برای عملکرد کامل همه را بپذیرید.")
            requestSpecialPermissions()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSetup()

        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        if (!prefs.getBoolean("first_run_done", false)) {
            requestAllPermissionsFirstRun()
        }
    }

    private fun requestAllPermissionsFirstRun() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
        }

        val needed = list.filter {
            try {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }

        if (needed.isNotEmpty()) {
            showStatus("لطفاً همه مجوزها را برای عملکرد کامل بپذیرید")
            permissionsLauncher.launch(needed.toTypedArray())
        } else {
            requestSpecialPermissions()
        }
    }

    private fun requestSpecialPermissions() {
        // Overlay
        if (!Settings.canDrawOverlays(this)) {
            showStatus("مجوز نمایش روی برنامه‌های دیگر لازم است")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        // Full storage access (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }

        createGrokFolder()
        getSharedPreferences("aria", MODE_PRIVATE).edit()
            .putBoolean("first_run_done", true)
            .apply()
    }

    private fun createGrokFolder() {
        try {
            val mem = PersistentMemory(this)
            mem.saveNote("_welcome", "پوشه حافظه Grok آماده است.\nمسیر: ${mem.getRootPath()}", listOf("system"))
            showStatus("پوشه Grok آماده: ${mem.getRootPath()}")
        } catch (e: Exception) {
            // fallback
            try {
                val dir = File(Environment.getExternalStorageDirectory(), "Grok")
                if (!dir.exists()) dir.mkdirs()
            } catch (_: Exception) {
                File(filesDir, "Grok").mkdirs()
            }
        }
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
            setPadding(0, 0, 0, 12)
        }
        root.addView(activeProviderLabel)

        // Quick actions
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        actions.addView(Button(this).apply {
            text = "💬 چت متنی"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ChatActivity::class.java))
            }
        })
        actions.addView(Button(this).apply {
            text = "🔐 همه مجوزها"
            setOnClickListener { requestAllPermissionsFirstRun() }
        })
        root.addView(actions)

        // Providers
        val groqLayout = collapsibleSection(root, "۱. Groq (رایگان و پرسرعت)", "https://console.groq.com/keys")
        groqKey = passField("Groq API Key", prefs.getString("groq_api_key", "") ?: "")
        groqLayout.addView(groqKey)
        groqModel = textField("مدل", prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile")
        groqLayout.addView(groqModel)
        addModelHints(groqLayout, UnoRouterProvider.GROQ_DEFAULT_MODELS)
        groqLayout.addView(Button(this).apply {
            text = "تست و فعال‌سازی Groq"
            setOnClickListener { testProvider("groq") }
        })
        groqStatus = statusView(prefs.getBoolean("groq_test_ok", false))
        groqLayout.addView(groqStatus)

        val orLayout = collapsibleSection(root, "۲. OpenRouter", "https://openrouter.ai/keys")
        openRouterKey = passField("OpenRouter API Key", prefs.getString("openrouter_api_key", "") ?: "")
        orLayout.addView(openRouterKey)
        openRouterModel = textField("مدل", prefs.getString("openrouter_model", "google/gemini-flash-1.5") ?: "google/gemini-flash-1.5")
        orLayout.addView(openRouterModel)
        orLayout.addView(Button(this).apply {
            text = "تست و فعال‌سازی OpenRouter"
            setOnClickListener { testProvider("openrouter") }
        })
        openRouterStatus = statusView(prefs.getBoolean("openrouter_test_ok", false))
        orLayout.addView(openRouterStatus)

        val xaiLayout = collapsibleSection(root, "۳. xAI / Grok", "https://console.x.ai")
        xaiKey = passField("xAI API Key", prefs.getString("xai_api_key", "") ?: "")
        xaiLayout.addView(xaiKey)
        xaiModel = textField("مدل", prefs.getString("xai_model", "grok-3") ?: "grok-3")
        xaiLayout.addView(xaiModel)
        addModelHints(xaiLayout, UnoRouterProvider.XAI_DEFAULT_MODELS)
        xaiLayout.addView(Button(this).apply {
            text = "تست و فعال‌سازی xAI / Grok"
            setOnClickListener { testProvider("xai") }
        })
        xaiStatus = statusView(prefs.getBoolean("xai_test_ok", false))
        xaiLayout.addView(xaiStatus)

        val customLayout = collapsibleSection(root, "۴. سفارشی / پروکسی API", null)
        customUrl = textField("آدرس API", prefs.getString("custom_api_url", "") ?: "")
        customLayout.addView(customUrl)
        customKey = passField("کلید API", prefs.getString("custom_api_key", "") ?: "")
        customLayout.addView(customKey)
        customModel = textField("مدل", prefs.getString("custom_model", "") ?: "")
        customLayout.addView(customModel)
        customLayout.addView(Button(this).apply {
            text = "تست و فعال‌سازی سفارشی"
            setOnClickListener { testProvider("custom") }
        })
        customStatus = statusView(prefs.getBoolean("custom_test_ok", false))
        customLayout.addView(customStatus)

        // Widget
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
                prefs.edit().putBoolean("floating_widget_enabled", enabled).apply()
                if (enabled) ensureRunPermissions()
                else {
                    stopService(Intent(this@MainActivity, AriaOverlayService::class.java))
                    showStatus("ویجت خاموش شد")
                }
            }
        }
        root.addView(widgetSwitch)

        root.addView(Button(this).apply {
            text = "💾 ذخیره و اجرای ویجت"
            setOnClickListener {
                saveFields()
                prefs.edit().putBoolean("floating_widget_enabled", true).apply()
                widgetSwitch.isChecked = true
                ensureRunPermissions()
            }
        })

        root.addView(TextView(this).apply {
            text = "💡 ویجت پس از ۴۰ ثانیه سکوت مخفی می‌شود. از نوتیفیکیشن دوباره باز کنید.\n📁 حافظه: پوشه Grok روی حافظه گوشی"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 16, 0, 0)
        })

        setContentView(scroll)
    }

    private fun passField(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
    }

    private fun textField(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
    }

    private fun statusView(ok: Boolean) = TextView(this).apply {
        text = "وضعیت: " + if (ok) "موفق ✅" else "غیرفعال ❌"
        textSize = 13f
        setTextColor(Color.LTGRAY)
        setPadding(0, 4, 0, 8)
    }

    private fun collapsibleSection(parent: LinearLayout, title: String, regUrl: String?): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 8, 16, 16)
        }
        parent.addView(TextView(this).apply {
            text = "▼ $title"
            textSize = 15f
            setTextColor(Color.parseColor("#90CAF9"))
            setPadding(0, 14, 0, 6)
            setOnClickListener {
                section.visibility = if (section.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        })
        if (regUrl != null) {
            section.addView(TextView(this).apply {
                text = "🔑 دریافت کلید: $regUrl"
                textSize = 12f
                setTextColor(Color.parseColor("#64B5F6"))
                setPadding(0, 0, 0, 6)
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(regUrl))) }
            })
        }
        parent.addView(section)
        return section
    }

    private fun addModelHints(parent: LinearLayout, models: List<String>) {
        parent.addView(TextView(this).apply {
            text = "مدل‌ها: ${models.joinToString(" | ")}"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 2, 0, 6)
        })
    }

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
            "groq" -> Triple(groqKey.text.toString().trim(), UnoRouterProvider.GROQ_BASE, groqModel.text.toString().trim().ifBlank { "llama-3.3-70b-versatile" })
            "openrouter" -> Triple(openRouterKey.text.toString().trim(), UnoRouterProvider.OPENROUTER_BASE, openRouterModel.text.toString().trim())
            "xai" -> Triple(xaiKey.text.toString().trim(), UnoRouterProvider.XAI_BASE, xaiModel.text.toString().trim().ifBlank { "grok-3" })
            else -> Triple(customKey.text.toString().trim(), customUrl.text.toString().trim(), customModel.text.toString().trim())
        }
        showStatus("تست $which...")
        Thread {
            val result = ApiConnectionTester.test(key, url, model)
            runOnUiThread {
                prefs.edit().putBoolean("${which}_test_ok", result.success).apply()
                if (result.success) {
                    prefs.edit().putString("active_provider", which).apply()
                    activeProviderLabel.text = "ارائه‌دهنده فعال: $which"
                }
                val st = "وضعیت: " + if (result.success) "موفق ✅" else "ناموفق ❌"
                when (which) {
                    "groq" -> groqStatus.text = st
                    "openrouter" -> openRouterStatus.text = st
                    "xai" -> xaiStatus.text = st
                    else -> customStatus.text = st
                }
                showStatus("$which: ${result.message}")
            }
        }.start()
    }

    private fun ensureRunPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            showStatus("مجوز نمایش روی برنامه‌های دیگر لازم است")
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
        createGrokFolder()
        startAria()
    }

    private fun startAria() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, AriaOverlayService::class.java))
            showStatus("ویجت فعال شد. ضربه بزنید و صحبت کنید.")
        } catch (e: Exception) {
            showStatus("خطا: ${e.message}")
        }
    }

    private fun showStatus(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
