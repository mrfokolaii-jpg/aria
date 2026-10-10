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
import com.mrfokolaii.aria.voice.OfflinePersianTts
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
    private lateinit var offlineTtsStatus: TextView

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it } || results[Manifest.permission.RECORD_AUDIO] == true) {
            requestSpecialPermissions()
        } else {
            showStatus("برخی مجوزها رد شدند.")
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
            } catch (_: Exception) { false }
        }
        if (needed.isNotEmpty()) permissionsLauncher.launch(needed.toTypedArray())
        else requestSpecialPermissions()
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
            setPadding(32, 28, 32, 32)
            setBackgroundColor(Color.parseColor("#121212"))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "🤖 تنظیمات ARIA"
            textSize = 22f
            setTextColor(Color.WHITE)
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
            text = "💬 چت"
            setOnClickListener { startActivity(Intent(this@MainActivity, ChatActivity::class.java)) }
        })
        actions.addView(Button(this).apply {
            text = "🔐 مجوزها"
            setOnClickListener { requestAllPermissionsFirstRun() }
        })
        root.addView(actions)

        // Offline Persian TTS
        root.addView(TextView(this).apply {
            text = "🎙 موتور فارسی آفلاین (داخل اپ)"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 8)
        })
        val off = OfflinePersianTts(this)
        offlineTtsStatus = TextView(this).apply {
            text = offlineStatusText(off)
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 8)
        }
        root.addView(offlineTtsStatus)
        root.addView(Button(this).apply {
            text = "⬇️ دانلود مدل فارسی آفلاین (یک‌بار با اینترنت)"
            setOnClickListener { downloadOfflineTts() }
        })
        root.addView(TextView(this).apply {
            text = "بعد از دانلود، بدون اینترنت با صدای عصبی فارسی صحبت می‌کند."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 12)
        })

        // Providers
        val groqLayout = collapsibleSection(root, "۱. Groq", "https://console.groq.com/keys")
        groqKey = passField("Groq API Key", prefs.getString("groq_api_key", "") ?: "")
        groqLayout.addView(groqKey)
        groqModel = textField("مدل", prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile")
        groqLayout.addView(groqModel)
        groqLayout.addView(Button(this).apply { text = "تست Groq"; setOnClickListener { testProvider("groq") } })
        groqStatus = statusView(prefs.getBoolean("groq_test_ok", false))
        groqLayout.addView(groqStatus)

        val orLayout = collapsibleSection(root, "۲. OpenRouter", "https://openrouter.ai/keys")
        openRouterKey = passField("OpenRouter Key", prefs.getString("openrouter_api_key", "") ?: "")
        orLayout.addView(openRouterKey)
        openRouterModel = textField("مدل", prefs.getString("openrouter_model", "google/gemini-flash-1.5") ?: "google/gemini-flash-1.5")
        orLayout.addView(openRouterModel)
        orLayout.addView(Button(this).apply { text = "تست OpenRouter"; setOnClickListener { testProvider("openrouter") } })
        openRouterStatus = statusView(prefs.getBoolean("openrouter_test_ok", false))
        orLayout.addView(openRouterStatus)

        val xaiLayout = collapsibleSection(root, "۳. xAI / Grok", "https://console.x.ai")
        xaiKey = passField("xAI Key", prefs.getString("xai_api_key", "") ?: "")
        xaiLayout.addView(xaiKey)
        xaiModel = textField("مدل", prefs.getString("xai_model", "grok-3") ?: "grok-3")
        xaiLayout.addView(xaiModel)
        xaiLayout.addView(Button(this).apply { text = "تست xAI"; setOnClickListener { testProvider("xai") } })
        xaiStatus = statusView(prefs.getBoolean("xai_test_ok", false))
        xaiLayout.addView(xaiStatus)

        val customLayout = collapsibleSection(root, "۴. سفارشی", null)
        customUrl = textField("URL", prefs.getString("custom_api_url", "") ?: "")
        customLayout.addView(customUrl)
        customKey = passField("Key", prefs.getString("custom_api_key", "") ?: "")
        customLayout.addView(customKey)
        customModel = textField("Model", prefs.getString("custom_model", "") ?: "")
        customLayout.addView(customModel)
        customLayout.addView(Button(this).apply { text = "تست سفارشی"; setOnClickListener { testProvider("custom") } })
        customStatus = statusView(prefs.getBoolean("custom_test_ok", false))
        customLayout.addView(customStatus)

        root.addView(TextView(this).apply {
            text = "⚙️ ویجت"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 8)
        })
        widgetSwitch = Switch(this).apply {
            text = "ویجت شناور"
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
            text = "💾 ذخیره و اجرای ویجت"
            setOnClickListener {
                saveFields()
                prefs.edit().putBoolean("floating_widget_enabled", true).apply()
                widgetSwitch.isChecked = true
                ensureRunPermissions()
            }
        })

        setContentView(scroll)
    }

    private fun offlineStatusText(off: OfflinePersianTts): String =
        when {
            off.isReady() -> "✅ موتور عصبی فارسی آماده (آفلاین)"
            off.isModelDownloaded() -> "📦 مدل دانلود شده — در حال آماده‌سازی..."
            else -> "⬜ هنوز دانلود نشده (حدود ۶۰–۹۰ مگ)"
        }

    private fun downloadOfflineTts() {
        val off = OfflinePersianTts(this)
        if (off.isReady()) {
            showStatus("موتور از قبل آماده است")
            offlineTtsStatus.text = offlineStatusText(off)
            return
        }
        showStatus("شروع دانلود مدل فارسی...")
        Thread {
            val ok = off.downloadModel { msg ->
                runOnUiThread { offlineTtsStatus.text = msg }
            }
            runOnUiThread {
                offlineTtsStatus.text = offlineStatusText(off)
                showStatus(if (ok) "مدل فارسی آفلاین آماده شد" else "دانلود ناموفق — اینترنت را چک کن")
            }
        }.start()
    }

    private fun passField(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(value); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
    }

    private fun textField(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setSingleLine(true)
        setText(value); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
    }

    private fun statusView(ok: Boolean) = TextView(this).apply {
        text = "وضعیت: " + if (ok) "موفق ✅" else "غیرفعال ❌"
        textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 4, 0, 8)
    }

    private fun collapsibleSection(parent: LinearLayout, title: String, regUrl: String?): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(16, 8, 16, 16)
        }
        parent.addView(TextView(this).apply {
            text = "▼ $title"; textSize = 15f; setTextColor(Color.parseColor("#90CAF9"))
            setPadding(0, 14, 0, 6)
            setOnClickListener {
                section.visibility = if (section.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        })
        if (regUrl != null) {
            section.addView(TextView(this).apply {
                text = "🔑 $regUrl"; textSize = 12f; setTextColor(Color.parseColor("#64B5F6"))
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(regUrl))) }
            })
        }
        parent.addView(section)
        return section
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
        Thread {
            val result = ApiConnectionTester.test(key, url, model)
            runOnUiThread {
                prefs.edit().putBoolean("${which}_test_ok", result.success).apply()
                if (result.success) {
                    prefs.edit().putString("active_provider", which).apply()
                    activeProviderLabel.text = "ارائه‌دهنده فعال: $which"
                }
                showStatus("$which: ${result.message}")
            }
        }.start()
    }

    private fun ensureRunPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val needed = list.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) { permissionsLauncher.launch(needed.toTypedArray()); return }
        createGrokFolder()
        startAria()
    }

    private fun startAria() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, AriaOverlayService::class.java))
            showStatus("ویجت فعال شد")
        } catch (e: Exception) { showStatus("خطا: ${e.message}") }
    }

    private fun showStatus(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
