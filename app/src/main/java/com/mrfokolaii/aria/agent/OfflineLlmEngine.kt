package com.mrfokolaii.aria.agent

import android.content.Context
import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device chat with Qwen2.5-1.5B-Instruct GGUF (no API key).
 */
class OfflineLlmEngine(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var modelHandle: Any? = null
    @Volatile private var loadError: String? = null

    fun modelFile(): File =
        File(context.getExternalFilesDir(MODELS_DIR) ?: context.filesDir, MODEL_FILE_NAME)

    fun isModelPresent(): Boolean {
        val f = modelFile()
        return f.exists() && f.length() > 50_000_000L
    }

    fun modelStatusText(): String {
        val f = modelFile()
        return when {
            !f.exists() -> "مدل نصب نیست — از تنظیمات دانلود کن"
            f.length() < 50_000_000L -> "فایل مدل ناقص است — دوباره دانلود کن"
            modelHandle != null -> "مدل آفلاین آماده است"
            loadError != null -> "خطا در بارگذاری: $loadError"
            else -> "مدل موجود است — آماده بارگذاری"
        }
    }

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (modelHandle != null) return@withContext true
            val f = modelFile()
            if (!f.exists() || f.length() < 50_000_000L) {
                loadError = "فایل مدل پیدا نشد"
                return@withContext false
            }
            try {
                val handle = Llama.loadModel(
                    modelPath = f.absolutePath,
                    config = LlamaConfig(
                        contextSize = 2048,
                        threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                    )
                )
                modelHandle = handle
                loadError = null
                Log.i(TAG, "Model loaded: ${f.length() / 1_000_000}MB")
                true
            } catch (e: Exception) {
                loadError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "loadModel failed", e)
                false
            }
        }
    }

    suspend fun chat(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (!ensureLoaded()) {
            return@withContext "مدل آفلاین آماده نیست.\n" +
                "فایل Qwen را دانلود کن و در پوشه مدل اپ بگذار:\n" +
                modelFile().absolutePath
        }
        val handle = modelHandle ?: return@withContext "مدل بارگذاری نشد."

        val prompt = buildString {
            history.takeLast(6).forEach { (role, content) ->
                if (role == "user") append("User: ${content.take(400)}\n")
                else append("Assistant: ${content.take(400)}\n")
            }
            append("User: ${userMessage.take(800)}\nAssistant:")
        }

        try {
            mutex.withLock {
                @Suppress("UNCHECKED_CAST")
                val result = Llama.complete(
                    handle as dev.ffmpegkit.llama.LlamaModel,
                    prompt = prompt,
                    systemPrompt = SYSTEM_PROMPT,
                    maxTokens = 256
                )
                result.text.trim().ifBlank { "پاسخی تولید نشد." }
            }
        } catch (e: ClassCastException) {
            // Fallback if type name differs across library versions
            try {
                val complete = Llama::class.java.methods.first {
                    it.name == "complete" && it.parameterTypes.size >= 2
                }
                val result = complete.invoke(null, handle, prompt, SYSTEM_PROMPT, 256)
                val text = result?.javaClass?.getMethod("getText")?.invoke(result)
                    ?: result?.javaClass?.getField("text")?.get(result)
                text?.toString()?.trim()?.ifBlank { "پاسخی تولید نشد." } ?: "پاسخی تولید نشد."
            } catch (e2: Exception) {
                Log.e(TAG, "complete failed", e2)
                "خطا در مدل آفلاین: ${e2.message}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "complete failed", e)
            "خطا در مدل آفلاین: ${e.message}"
        }
    }

    fun release() {
        val h = modelHandle ?: return
        try {
            Llama.releaseModel(h as dev.ffmpegkit.llama.LlamaModel)
        } catch (_: Exception) {
            try {
                Llama::class.java.methods.first { it.name == "releaseModel" }.invoke(null, h)
            } catch (_: Exception) {}
        }
        modelHandle = null
    }

    companion object {
        private const val TAG = "OfflineLlmEngine"
        const val MODELS_DIR = "models"
        const val MODEL_FILE_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"

        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"

        const val MODEL_PAGE_URL =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF"

        private val SYSTEM_PROMPT = """
تو آریا هستی، دستیار هوشمند فارسی روی اندروید.
پاسخ کوتاه، واضح و دوستانه به فارسی بده.
اگر دستور اجرایی است فرض کن ابزار گوشی آن را انجام داده؛ فقط نتیجه کوتاه بگو.
""".trim()
    }
}
