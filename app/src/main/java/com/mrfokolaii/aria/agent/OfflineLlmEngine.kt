package com.mrfokolaii.aria.agent

import android.content.Context
import android.os.Environment
import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** On-device Qwen2.5-1.5B GGUF — tuned for lower latency on phone CPU. */
class OfflineLlmEngine(private val context: Context) {

    fun candidateModelFiles(): List<File> {
        val name = MODEL_FILE_NAME
        val list = mutableListOf<File>()
        context.getExternalFilesDir(MODELS_DIR)?.let { list.add(File(it, name)) }
        list.add(File(File(context.filesDir, MODELS_DIR), name))
        try {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            list.add(File(dl, name))
            list.add(File(File(dl, "Aria"), name))
        } catch (_: Exception) {}
        list.add(File("/storage/emulated/0/Download/$name"))
        list.add(File("/sdcard/Download/$name"))
        return list.distinctBy { it.absolutePath }
    }

    fun modelFile(): File =
        candidateModelFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
            ?: (context.getExternalFilesDir(MODELS_DIR)?.let { File(it, MODEL_FILE_NAME) }
                ?: File(context.filesDir, "$MODELS_DIR/$MODEL_FILE_NAME"))

    fun isModelPresent(): Boolean =
        candidateModelFiles().any { it.exists() && it.length() > 50_000_000L }

    fun modelStatusText(): String {
        val found = candidateModelFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
        return when {
            sharedModel != null && found != null ->
                "مدل آفلاین بارگذاری شده\n${found.absolutePath}"
            found != null ->
                "مدل آماده: ${found.name} (${found.length() / 1_000_000} MB)\n${found.absolutePath}"
            lastLoadError != null ->
                "خطا:\n$lastLoadError"
            else ->
                "مدل پیدا نشد — از تنظیمات آپلود کن"
        }
    }

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (sharedModel != null) return@withContext true
            val f = candidateModelFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
            if (f == null) {
                lastLoadError = "فایل GGUF پیدا نشد"
                return@withContext false
            }
            try {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                Log.i(TAG, "Loading ${f.absolutePath} (${f.length() / 1_000_000}MB) threads=$threads")
                // Smaller context = faster prompt eval on CPU
                val config = LlamaConfig(contextSize = 1024, threads = threads)
                val model = Llama.loadModel(modelPath = f.absolutePath, config = config)
                sharedModel = model
                lastLoadError = null
                loadedPath = f.absolutePath
                Log.i(TAG, "Model loaded successfully")
                true
            } catch (e: Throwable) {
                lastLoadError = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Llama.loadModel failed", e)
                false
            }
        }
    }

    suspend fun chat(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (!ensureLoaded()) {
            return@withContext buildString {
                appendLine("مدل آفلاین آماده نیست.")
                lastLoadError?.let { appendLine("علت: $it") }
                appendLine("مسیر فایل:")
                appendLine(modelFile().absolutePath)
            }
        }
        val model = sharedModel ?: return@withContext "مدل null است."

        val prompt = buildString {
            history.takeLast(3).forEach { (role, content) ->
                if (role == "user") append("User: ${content.take(200)}\n")
                else append("Assistant: ${content.take(200)}\n")
            }
            append("User: ${userMessage.take(500)}\nAssistant:")
        }

        try {
            loadMutex.withLock {
                val result = Llama.complete(
                    model = model,
                    prompt = prompt,
                    systemPrompt = SYSTEM_PROMPT,
                    maxTokens = 120
                )
                result.text.trim().ifBlank { "پاسخی تولید نشد." }
            }
        } catch (e: Exception) {
            Log.e(TAG, "complete failed", e)
            "خطا در تولید پاسخ: ${e.message}"
        }
    }

    fun release() {}

    companion object {
        private const val TAG = "OfflineLlmEngine"
        const val MODELS_DIR = "models"
        const val MODEL_FILE_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"

        private val loadMutex = Mutex()
        @Volatile var sharedModel: LlamaModel? = null
            private set
        @Volatile var lastLoadError: String? = null
            private set
        @Volatile var loadedPath: String? = null
            private set

        private val SYSTEM_PROMPT =
            "تو آریا هستی. فقط فارسی، خیلی کوتاه و مستقیم جواب بده. سلام اضافه نکن مگر کاربر سلام کند."
    }
}
