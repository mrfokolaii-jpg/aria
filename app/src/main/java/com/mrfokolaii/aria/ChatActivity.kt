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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mrfokolaii.aria.agent.AgentBridge
import com.mrfokolaii.aria.agent.UnoRouterProvider
import com.mrfokolaii.aria.memory.ChatHistoryStore
import com.mrfokolaii.aria.memory.PersistentMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var chatStore: ChatHistoryStore
    private lateinit var knowledgeMemory: PersistentMemory
    private var agentBridge: AgentBridge? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
        chatStore = ChatHistoryStore(this)
        knowledgeMemory = PersistentMemory(this)
        initBridge()
        buildUi()
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        reloadBubbles()
    }

    private fun initBridge() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        val provider = prefs.getString("active_provider", "groq") ?: "groq"
        val (key, endpoint, model) = when (provider) {
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
            "custom" -> Triple(
                prefs.getString("custom_api_key", "") ?: "",
                prefs.getString("custom_api_url", "") ?: "",
                prefs.getString("custom_model", "") ?: ""
            )
            else -> Triple(
                prefs.getString("groq_api_key", "") ?: "",
                UnoRouterProvider.GROQ_BASE,
                prefs.getString("groq_model", "openai/gpt-oss-20b") ?: "openai/gpt-oss-20b"
            )
        }
        agentBridge = AgentBridge(this, key, endpoint, model)
    }

    private fun buildUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.parseColor("#0F0F1A")
        window.navigationBarColor = Color.parseColor("#0F0F1A")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F1A"))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#16162A"))
            setPadding(20, 16, 20, 16)
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(TextView(this).apply {
            text = "چت آریا"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        toolbar.addView(Button(this).apply {
            text = "پاک"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                chatStore.clear()
                reloadBubbles()
                addBubble("assistant", "تاریخچه پاک شد.", "chat")
            }
        })
        toolbar.addView(Button(this).apply {
            text = "⚙"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(this@ChatActivity, MainActivity::class.java))
            }
        })
        root.addView(toolbar)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 14, 14, 14)
        }
        scrollView.addView(chatContainer)
        root.addView(scrollView)

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#16162A"))
            setPadding(12, 14, 12, 14)
            gravity = Gravity.CENTER_VERTICAL
        }

        inputBar.addView(Button(this).apply {
            text = "📎"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener { filePicker.launch("*/*") }
        })

        inputField = EditText(this).apply {
            hint = "پیام خود را بنویسید..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#252540"))
            setPadding(22, 18, 22, 18)
            minHeight = 52
            maxLines = 5
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
                marginEnd = 8
            }
        }
        inputBar.addView(inputField)

        inputBar.addView(Button(this).apply {
            text = "➤"
            textSize = 18f
            setBackgroundColor(Color.parseColor("#00C853"))
            setTextColor(Color.WHITE)
            setOnClickListener { sendMessage() }
        })

        root.addView(inputBar)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }

        setContentView(root)
    }

    private fun loadHistory() {
        reloadBubbles()
        if (chatStore.messageCount() == 0) {
            addBubble(
                "assistant",
                "سلام! من آریا هستم. بنویس یا از ویجت صحبت کن.",
                "chat"
            )
        }
    }

    private fun reloadBubbles() {
        if (!::chatContainer.isInitialized) return
        chatContainer.removeAllViews()
        chatStore.loadAll().forEach { msg ->
            addBubble(msg.role, msg.text, msg.source, scroll = false)
        }
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMessage() {
        if (isSending) return
        val text = inputField.text?.toString()?.trim().orEmpty()
        val fileUri = pendingFileUri
        if (text.isEmpty() && fileUri == null) return

        val display = buildString {
            if (text.isNotEmpty()) append(text)
            if (fileUri != null) {
                if (isNotEmpty()) append("\n")
                append("[فایل: ${fileUri.lastPathSegment}]")
            }
        }
        inputField.setText("")
        inputField.hint = "پیام خود را بنویسید..."
        pendingFileUri = null

        chatStore.append("user", display, "chat")
        addBubble("user", display, "chat")
        val thinking = addBubble("assistant", "...", "chat")
        isSending = true

        scope.launch {
            try {
                val history = chatStore.loadAll().map { it.role to it.text }.takeLast(20)
                val response = withContext(Dispatchers.IO) {
                    agentBridge?.processWithAgentTools(display, history)
                        ?: com.mrfokolaii.aria.agent.AgentResponse("پل ارتباطی آماده نیست.")
                }
                chatStore.append("assistant", response.text, "chat")
                updateBubble(thinking, response.text)
                response.toolResult?.data?.let { saveKnowledge(it) }
            } catch (e: Exception) {
                val err = "خطا: ${e.message}"
                chatStore.append("assistant", err, "chat")
                updateBubble(thinking, err)
            } finally {
                isSending = false
            }
        }
    }

    private fun saveKnowledge(data: Any?) {
        try {
            when (data) {
                is Map<*, *> -> {
                    val url = data["url"]?.toString()
                    if (url != null) {
                        knowledgeMemory.saveSite(
                            url = url,
                            title = data["title"]?.toString() ?: url,
                            categories = listOf("chat"),
                            features = listOf("linked"),
                            summary = data["description"]?.toString() ?: data["snippet"]?.toString() ?: ""
                        )
                    }
                }
                is List<*> -> {
                    data.forEach { item ->
                        if (item is Map<*, *>) {
                            val u = item["url"]?.toString() ?: return@forEach
                            knowledgeMemory.saveSite(
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

    private fun addBubble(role: String, text: String, source: String = "chat", scroll: Boolean = true): TextView {
        val isUser = role == "user"
        val prefix = if (source == "voice") "🎤 " else ""
        val bubble = TextView(this).apply {
            this.text = prefix + text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(28, 18, 28, 18)
            setBackgroundColor(
                when {
                    isUser && source == "voice" -> Color.parseColor("#0D47A1")
                    isUser -> Color.parseColor("#1565C0")
                    source == "voice" -> Color.parseColor("#1B3A2F")
                    else -> Color.parseColor("#2A2A3E")
                }
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
