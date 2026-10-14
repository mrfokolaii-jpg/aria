package com.mrfokolaii.aria

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
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
import com.mrfokolaii.aria.agent.AgentResponse
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
    private lateinit var inputRow: LinearLayout
    private lateinit var chatStore: ChatHistoryStore
    private lateinit var knowledgeMemory: PersistentMemory
    private var agentBridge: AgentBridge? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingFileUri: Uri? = null
    private var isSending = false
    private var shownCount = 0

    private val chatUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread { syncNewMessages() }
        }
    }

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

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ChatHistoryStore.ACTION_CHAT_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(chatUpdatedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(chatUpdatedReceiver, filter)
        }
    }

    override fun onStop() {
        try { unregisterReceiver(chatUpdatedReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        initBridge()
        syncNewMessages()
    }

    private fun initBridge() {
        val prefs = getSharedPreferences("aria", MODE_PRIVATE)
        fun load(provider: String): Triple<String, String, String> = when (provider) {
            "openrouter" -> Triple(
                prefs.getString("openrouter_api_key", "") ?: "",
                UnoRouterProvider.OPENROUTER_BASE,
                prefs.getString("openrouter_model", "openrouter/free") ?: "openrouter/free"
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
        var provider = prefs.getString("active_provider", "groq") ?: "groq"
        var (key, endpoint, model) = load(provider)
        if (key.isBlank()) {
            for (p in listOf("groq", "openrouter", "xai", "custom")) {
                val t = load(p)
                if (t.first.isNotBlank()) {
                    provider = p
                    key = t.first
                    endpoint = t.second
                    model = t.third
                    prefs.edit().putString("active_provider", p).apply()
                    break
                }
            }
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
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                chatStore.clear()
                chatContainer.removeAllViews()
                shownCount = 0
            }
        })
        toolbar.addView(Button(this).apply {
            text = "⚙"
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
            clipToPadding = false
        }
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
        }
        scrollView.addView(chatContainer)
        root.addView(scrollView)

        inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#16162A"))
            setPadding(12, 12, 12, 12)
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(Button(this).apply {
            text = "📎"
            setBackgroundColor(Color.parseColor("#2A2A40"))
            setTextColor(Color.WHITE)
            setOnClickListener { filePicker.launch("*/*") }
        })
        inputField = EditText(this).apply {
            hint = "پیام خود را بنویسید..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#252540"))
            setPadding(20, 16, 20, 16)
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
                marginEnd = 8
            }
        }
        inputRow.addView(inputField)
        inputRow.addView(Button(this).apply {
            text = "➤"
            setBackgroundColor(Color.parseColor("#00C853"))
            setTextColor(Color.WHITE)
            setOnClickListener { sendMessage() }
        })
        root.addView(inputRow)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            v.setPadding(0, bars.top, 0, bottom)
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            insets
        }

        setContentView(root)
        inputField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scrollView.postDelayed({ scrollView.fullScroll(View.FOCUS_DOWN) }, 120)
            }
        }
    }

    private fun loadHistory() {
        chatContainer.removeAllViews()
        shownCount = 0
        chatStore.loadAll().forEach { msg ->
            addBubble(msg.role, msg.text, msg.source, scroll = false)
            shownCount++
        }
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun syncNewMessages() {
        if (!::chatContainer.isInitialized) return
        if (isSending) return
        val all = chatStore.loadAll()
        if (all.size < shownCount) {
            loadHistory()
            return
        }
        if (all.size == shownCount) return
        for (i in shownCount until all.size) {
            val msg = all[i]
            addBubble(msg.role, msg.text, msg.source, scroll = true)
        }
        shownCount = all.size
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

        isSending = true
        chatStore.append("user", display, "chat")
        addBubble("user", display, "chat")
        shownCount = chatStore.messageCount()
        val thinking = addBubble("assistant", "در حال خواندن حافظه و فکر کردن…", "chat")

        scope.launch {
            try {
                initBridge()
                val history = chatStore.loadAll().map { it.role to it.text }.takeLast(12)
                val response = withContext(Dispatchers.IO) {
                    agentBridge?.processWithAgentTools(display, history)
                        ?: AgentResponse("پل ارتباطی آماده نیست.")
                }
                chatStore.append("assistant", response.text, "chat")
                updateBubble(thinking, response.text)
                shownCount = chatStore.messageCount()
                response.toolResult?.data?.let { saveKnowledge(it) }
            } catch (e: Exception) {
                val err = "خطا: ${e.message}"
                chatStore.append("assistant", err, "chat")
                updateBubble(thinking, err)
                shownCount = chatStore.messageCount()
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
                    if (!url.isNullOrBlank()) {
                        knowledgeMemory.saveSite(
                            url = url,
                            title = data["title"]?.toString() ?: url,
                            categories = listOf("search"),
                            features = listOf("search_result"),
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
