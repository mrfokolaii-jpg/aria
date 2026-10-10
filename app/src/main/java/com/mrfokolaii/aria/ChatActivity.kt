package com.mrfokolaii.aria

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mrfokolaii.aria.agent.AgentBridge
import com.mrfokolaii.aria.agent.UnoRouterProvider
import com.mrfokolaii.aria.memory.PersistentMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var memory: PersistentMemory
    private var agentBridge: AgentBridge? = null
    private val history = mutableListOf<Pair<String, String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessionId = ""
    private var pendingFileUri: Uri? = null
    private var isSending = false

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingFileUri = uri
            val name = uri.lastPathSegment ?: "فایل"
            Toast.makeText(this, "فایل آماده ارسال: $name", Toast.LENGTH_SHORT).show()
            inputField.hint = "پیام + فایل: $name"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memory = PersistentMemory(this)
        sessionId = intent.getStringExtra("session_id")
            ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + UUID.randomUUID().toString().take(6)
        initBridge()
        buildUi()
        loadHistory()
    }

    private fun initBridge() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        val provider = prefs.getString("active_provider", "groq") ?: "groq"
        val (key, endpoint, model) = when (provider) {
            "groq" -> Triple(
                prefs.getString("groq_api_key", "") ?: "",
                UnoRouterProvider.GROQ_BASE,
                prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
            )
            "openrouter" -> Triple(
                prefs.getString("openrouter_api_key", "") ?: "",
                UnoRouterProvider.OPENROUTER_BASE,
                prefs.getString("openrouter_model", "google/gemini-flash-1.5") ?: "google/gemini-flash-1.5"
            )
            "xai" -> Triple(
                prefs.getString("xai_api_key", "") ?: "",
                UnoRouterProvider.XAI_BASE,
                prefs.getString("xai_model", "grok-3") ?: "grok-3"
            )
            else -> Triple(
                prefs.getString("custom_api_key", "") ?: "",
                prefs.getString("custom_api_url", "") ?: "",
                prefs.getString("custom_model", "") ?: ""
            )
        }
        agentBridge = AgentBridge(this, key, endpoint, model)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            setPadding(0, 0, 0, 0)
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(24, 28, 24, 20)
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(TextView(this).apply {
            text = "💬 چت آریا"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        toolbar.addView(Button(this).apply {
            text = "+‌جدید"
            textSize = 12f
            setOnClickListener { startNewSession() }
        })
        toolbar.addView(Button(this).apply {
            text = "⚙"
            textSize = 14f
            setOnClickListener {
                startActivity(Intent(this@ChatActivity, MainActivity::class.java))
            }
        })
        root.addView(toolbar)

        // Chat area
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        scrollView.addView(chatContainer)
        root.addView(scrollView)

        // Input bar
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(12, 12, 12, 12)
            gravity = Gravity.CENTER_VERTICAL
        }

        inputBar.addView(Button(this).apply {
            text = "📎"
            textSize = 16f
            setOnClickListener { filePicker.launch("*/*") }
        })

        inputField = EditText(this).apply {
            hint = "پیام خود را بنویسید..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A3E"))
            setPadding(20, 16, 20, 16)
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
                marginEnd = 8
            }
        }
        inputBar.addView(inputField)

        inputBar.addView(Button(this).apply {
            text = "➤"
            textSize = 18f
            setOnClickListener { sendMessage() }
        })

        root.addView(inputBar)
        setContentView(root)
    }

    private fun loadHistory() {
        val saved = memory.loadConversation(sessionId)
        if (saved.isEmpty()) {
            addBubble("assistant", "سلام! من آریا هستم. می‌تونی متن بنویسی، فایل بفرستی، یا از من بخوای جستجو و نکته‌برداری کنم.")
            return
        }
        history.clear()
        history.addAll(saved)
        saved.forEach { (role, text) -> addBubble(role, text, scroll = false) }
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startNewSession() {
        sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + UUID.randomUUID().toString().take(6)
        history.clear()
        chatContainer.removeAllViews()
        addBubble("assistant", "صفحه جدید. چطور کمکت کنم؟")
    }

    private fun sendMessage() {
        if (isSending) return
        var text = inputField.text.toString().trim()
        val fileUri = pendingFileUri

        if (text.isBlank() && fileUri == null) return

        if (fileUri != null) {
            val name = fileUri.lastPathSegment ?: "فایل"
            text = if (text.isBlank()) "[فایل پیوست: $name]" else "$text\n[فایل پیوست: $name]"
            // Note: binary analysis of images/zips needs vision API; for now we pass the name
            pendingFileUri = null
            inputField.hint = "پیام خود را بنویسید..."
        }

        inputField.setText("")
        addBubble("user", text)
        history.add("user" to text)
        memory.appendConversation(sessionId, "user", text)

        isSending = true
        val thinking = addBubble("assistant", "در حال فکر کردن...")

        scope.launch {
            try {
                val bridge = agentBridge
                if (bridge == null) {
                    updateBubble(thinking, "اتصال به هوش مصنوعی برقرار نیست. از تنظیمات کلید API را وارد کنید.")
                    return@launch
                }

                // Auto-note intent
                val lower = text.lowercase()
                if (lower.contains("یادداشت") || lower.contains("نکته") || lower.contains("ذخیره کن") || lower.contains("note")) {
                    memory.saveNote("auto_note_${System.currentTimeMillis()}", text, listOf("user_request"))
                }

                val response = withContext(Dispatchers.IO) {
                    bridge.processWithAgentTools(text, history.toList())
                }

                // If tool returned web data, auto-catalog sites
                response.toolResult?.data?.let { data ->
                    catalogFromToolData(data)
                }

                updateBubble(thinking, response.text)
                history.add("assistant" to response.text)
                memory.appendConversation(sessionId, "assistant", response.text)
            } catch (e: Exception) {
                updateBubble(thinking, "خطا: ${e.localizedMessage}")
            } finally {
                isSending = false
            }
        }
    }

    private fun catalogFromToolData(data: Any) {
        try {
            when (data) {
                is Map<*, *> -> {
                    val url = data["url"]?.toString()
                    val content = data["content"]?.toString() ?: ""
                    if (url != null && url.startsWith("http")) {
                        memory.saveSite(
                            url = url,
                            title = data["title"]?.toString() ?: url,
                            categories = listOf("web"),
                            features = listOf("browsed"),
                            summary = content.take(300)
                        )
                    }
                    @Suppress("UNCHECKED_CAST")
                    val results = data["results"] as? List<Map<String, Any>>
                    results?.forEach { item ->
                        val u = item["url"]?.toString() ?: return@forEach
                        memory.saveSite(
                            url = u,
                            title = item["title"]?.toString() ?: item["name"]?.toString() ?: u,
                            categories = listOf(item["language"]?.toString() ?: "search"),
                            features = listOf("search_result"),
                            summary = item["description"]?.toString() ?: item["snippet"]?.toString() ?: ""
                        )
                    }
                }
                is List<*> -> {
                    data.forEach { item ->
                        if (item is Map<*, *>) {
                            val u = item["url"]?.toString() ?: return@forEach
                            memory.saveSite(
                                url = u,
                                title = item["title"]?.toString() ?: u,
                                categories = listOf("search"),
                                features = listOf("search_result"),
                                summary = item["snippet"]?.toString() ?: ""
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun addBubble(role: String, text: String, scroll: Boolean = true): TextView {
        val isUser = role == "user"
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(28, 18, 28, 18)
            setBackgroundColor(
                if (isUser) Color.parseColor("#1565C0")
                else Color.parseColor("#2A2A3E")
            )
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
                topMargin = 8
                bottomMargin = 8
                marginStart = if (isUser) 80 else 8
                marginEnd = if (isUser) 8 else 80
            }
            layoutParams = lp
        }
        chatContainer.addView(bubble)
        if (scroll) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        return bubble
    }

    private fun updateBubble(view: TextView, text: String) {
        view.text = text
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
