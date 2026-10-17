package com.mrfokolaii.aria.agent

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.mrfokolaii.aria.memory.LocalKnowledgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Gemma-4 E4B only — LiteRT-LM with GPU preferred. */
class OfflineLlmEngine(private val context: Context) {

    fun requestStop() {
        globalCancel.set(true)
        Log.i(TAG, "Stop requested")
    }

    fun clearStop() {
        globalCancel.set(false)
    }

    private fun modelSearchDirs(): List<File> {
        val dirs = mutableListOf<File>()
        context.getExternalFilesDir(MODELS_DIR)?.let { dirs.add(it) }
        dirs.add(File(context.filesDir, MODELS_DIR))
        try {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dirs.add(dl)
            dirs.add(File(dl, "Aria"))
            dirs.add(File(dl, "models"))
        } catch (_: Exception) {}
        dirs.add(File("/storage/emulated/0/Download"))
        dirs.add(File("/sdcard/Download"))
        return dirs
    }

    fun candidateLitertFiles(): List<File> {
        val list = mutableListOf<File>()
        for (dir in modelSearchDirs()) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.filter {
                it.isFile &&
                    (it.name.contains("E4B", true) || it.name.equals(MODEL_FILE_NAME, true)) &&
                    it.name.endsWith(".litertlm", true) &&
                    it.length() > 100_000_000L
            }?.forEach { list.add(it) }
            list.add(File(dir, MODEL_FILE_NAME))
        }
        return list.distinctBy { it.absolutePath }
    }

    fun modelFile(): File =
        candidateLitertFiles().firstOrNull { it.exists() && it.length() > 100_000_000L }
            ?: (context.getExternalFilesDir(MODELS_DIR)?.let { File(it, MODEL_FILE_NAME) }
                ?: File(context.filesDir, "$MODELS_DIR/$MODEL_FILE_NAME"))

    fun isModelPresent(): Boolean =
        candidateLitertFiles().any { it.exists() && it.length() > 100_000_000L }

    fun listDetectedModels(): List<String> =
        candidateLitertFiles()
            .filter { it.exists() && it.length() > 100_000_000L }
            .map { "${it.name} (${it.length() / 1_000_000} MB)" }

    fun modelStatusText(): String {
        val f = candidateLitertFiles().firstOrNull { it.exists() && it.length() > 100_000_000L }
        return when {
            activeBackend == BackendKind.GPU && f != null ->
                "موتور: LiteRT-LM GPU\nGemma-4 E4B\n${f.name} (${f.length() / 1_000_000} MB)\n${f.absolutePath}"
            activeBackend == BackendKind.CPU && f != null ->
                "موتور: LiteRT-LM CPU\nGemma-4 E4B\n${f.name} (${f.length() / 1_000_000} MB)\n${f.absolutePath}"
            f != null ->
                "مدل پیدا شد (هنوز لود نشده)\n${f.name} (${f.length() / 1_000_000} MB)\n${f.absolutePath}"
            else -> "مدل نیست.\n$MODEL_DOWNLOAD_URL"
        }
    }

    private suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (sharedEngine != null) return@withContext true
        loadMutex.withLock {
            if (sharedEngine != null) return@withContext true
            val file = candidateLitertFiles().firstOrNull { it.exists() && it.length() > 100_000_000L }
            if (file == null) {
                lastLoadError = "فایل مدل پیدا نشد"
                return@withContext false
            }
            try {
                val cfg = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.GPU()
                )
                val eng = Engine(cfg)
                eng.initialize()
                sharedEngine = eng
                activeBackend = BackendKind.GPU
                loadedPath = file.absolutePath
                lastLoadError = null
                Log.i(TAG, "Loaded on GPU: ${file.name}")
                return@withContext true
            } catch (e: Throwable) {
                Log.w(TAG, "GPU load failed, trying CPU", e)
            }
            try {
                val cfg = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU()
                )
                val eng = Engine(cfg)
                eng.initialize()
                sharedEngine = eng
                activeBackend = BackendKind.CPU
                loadedPath = file.absolutePath
                lastLoadError = null
                Log.i(TAG, "Loaded on CPU")
                return@withContext true
            } catch (e2: Throwable) {
                lastLoadError = "CPU: ${e2.message}"
                sharedEngine = null
                return@withContext false
            }
        }
    }

    suspend fun chat(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        clearStop()
        if (!ensureLoaded()) {
            return@withContext "مدل Gemma-4 E4B آماده نیست.\n$lastLoadError\n$MODEL_DOWNLOAD_URL"
        }
        if (globalCancel.get()) return@withContext "متوقف شد."
        val engine = sharedEngine ?: return@withContext "موتور null"

        try {
            loadMutex.withLock {
                if (globalCancel.get()) return@withContext "متوقف شد."
                val config = ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_PROMPT)
                )
                val conversation = engine.createConversation(config)
                try {
                    val knowledge = try {
                        LocalKnowledgeStore(context).retrieveRelevant(userMessage, 2800)
                    } catch (_: Exception) { "" }
                    val prompt = buildString {
                        if (knowledge.isNotBlank()) {
                            append("دانش مرتبط از حافظه محلی:\n")
                            append(knowledge.take(2800))
                            append("\n---\n")
                        }
                        history.takeLast(4).forEach { (role, content) ->
                            if (role == "user") append("کاربر: ${content.take(180)}\n")
                            else append("آریا: ${content.take(180)}\n")
                        }
                        append(userMessage.take(1800))
                    }
                    if (globalCancel.get()) return@withContext "متوقف شد."
                    val resp = conversation.sendMessage(prompt)
                    if (globalCancel.get()) return@withContext "متوقف شد."
                    extractText(resp).ifBlank { resp.toString().take(2500) }
                } finally {
                    try { conversation.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            if (globalCancel.get()) return@withContext "متوقف شد."
            Log.e(TAG, "chat failed", e)
            "خطا: ${e.message}"
        }
    }

    private fun extractText(msg: Any?): String {
        if (msg == null) return ""
        return try {
            for (name in listOf("getText", "text", "getContent")) {
                try {
                    val m = msg.javaClass.methods.find { it.name == name && it.parameterCount == 0 }
                    val v = m?.invoke(msg)?.toString()
                    if (!v.isNullOrBlank() && !v.startsWith("Message") && v.length < 8000) return v.trim()
                } catch (_: Exception) {}
            }
            val s = msg.toString()
            Regex("text[=:]\\s*([^,\\]\\}]+)").find(s)?.groupValues?.getOrNull(1)?.trim() ?: s.take(3000)
        } catch (_: Exception) {
            msg.toString().take(2000)
        }
    }

    fun release() {
        requestStop()
        unloadAll()
    }

    enum class BackendKind { NONE, GPU, CPU }

    companion object {
        private const val TAG = "OfflineLlmEngine"
        private const val MODELS_DIR = "models"
        const val MODEL_FILE_NAME = "gemma-4-E4B-it.litertlm"
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/Gemma4-E4B-it/resolve/main/model.litertlm"

        private val loadMutex = Mutex()
        private val globalCancel = AtomicBoolean(false)

        @Volatile var sharedEngine: Engine? = null
            private set
        @Volatile var activeBackend: BackendKind = BackendKind.NONE
            private set
        @Volatile var loadedPath: String? = null
            private set
        @Volatile var lastLoadError: String? = null

        fun unloadAll() {
            try { sharedEngine?.close() } catch (_: Exception) {}
            sharedEngine = null
            activeBackend = BackendKind.NONE
            loadedPath = null
            lastLoadError = null
        }

        private const val SYSTEM_PROMPT =
            "تو آریا هستی، دستیار صوتی هوشمند روی گوشی اندروید. همیشه فارسی کوتاه و مفید جواب بده. " +
                "هرگز نگو که فقط مدل زبانی هستی یا نمی‌توانی صحبت کنی؛ صدای تو از طریق بلندگوی گوشی پخش می‌شود. " +
                "هرگز نگو اینترنت یا گیت‌هاب نداری؛ ابزارهای جستجو، تماس، پیامک و باز کردن اپ برایت فعال است. " +
                "اگر بخشی به نام دانش مرتبط از حافظه محلی آمد، اول از همان برای جواب استفاده کن. " +
                "سلام بی‌مورد نگو. اگر اطلاعات نداری کوتاه بگو نمی‌دانم. پاسخ‌ها را حداکثر چند جمله نگه دار."
    }
}
