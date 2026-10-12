package com.mrfokolaii.aria.agent

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device chat with Qwen2.5-1.5B-Instruct GGUF (no API key).
 * Looks for the model in app storage OR public Download folder.
 */
class OfflineLlmEngine(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var modelHandle: Any? = null
    @Volatile private var loadError: String? = null

    /** All places we accept the GGUF file (first existing wins). */
    fun candidateModelFiles(): List<File> {
        val name = MODEL_FILE_NAME
        val list = mutableListOf<File>()

        // 1) App private models dir
        context.getExternalFilesDir(MODELS_DIR)?.let { list.add(File(it, name)) }
        list.add(File(context.filesDir, "$MODELS_DIR/$name"))

        // 2) Public Download (easiest for user to copy)
        try {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            list.add(File(dl, name))
            list.add(File(dl, "Aria/$name"))
            list.add(File(dl, "aria/$name"))
        } catch (_: Exception) {}

        // 3) Common absolute paths on many phones
        list.add(File("/storage/emulated/0/Download/$name"))
        list.add(File("/storage/emulated/0/Download/Aria/$name"))
        list.add(File("/sdcard/Download/$name"))

        return list.distinctBy { it.absolutePath }
    }

    /** First existing valid model file, or preferred target path for messages. */
    fun modelFile(): File {
        return candidateModelFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
            ?: candidateModelFiles().first()
    }

    fun isModelPresent(): Boolean =
        candidateModelFiles().any { it.exists() && it.length() > 50_000_000L }

    fun modelStatusText(): String {
        val found = candidateModelFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
        return when {
            found != null && modelHandle != null -> "مدل آفلاین آماده است\n${found.absolutePath}"
            found != null -> "مدل پیدا شد — آماده بارگذاری\n${found.name}"
            loadError != null -> "خطا: $loadError"
            else -> "مدل پیدا نشد. فایل را در Download بگذار:\n$MODEL_FILE_NAME"
        }
    }

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (modelHandle != null) return@withContext true
            val f = candidateModelFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
            if (f == null) {
                loadError = "فایل مدل پیدا نشد"
                return@withContext false
            }
            try {
                val llamaClass = Class.forName("dev.ffmpegkit.llama.Llama")
                val configClass = Class.forName("dev.ffmpegkit.llama.LlamaConfig")
                val config = try {
                    configClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .newInstance(2048, Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
                } catch (_: Exception) {
                    configClass.getDeclaredConstructor().newInstance()
                }
                val load = llamaClass.methods.first { it.name == "loadModel" }
                val handle = when (load.parameterTypes.size) {
                    2 -> load.invoke(null, f.absolutePath, config)
                    1 -> load.invoke(null, f.absolutePath)
                    else -> load.invoke(null, f.absolutePath, config)
                }
                modelHandle = handle
                loadError = null
                Log.i(TAG, "Model loaded from ${f.absolutePath} (${f.length() / 1_000_000}MB)")
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
                "فایل را در پوشه Download گوشی بگذار با این نام:\n$MODEL_FILE_NAME\n\n" +
                "لینک:\n$MODEL_DOWNLOAD_URL"
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
                val llamaClass = Class.forName("dev.ffmpegkit.llama.Llama")
                val completeMethods = llamaClass.methods.filter { it.name == "complete" }
                var result: Any? = null
                var lastErr: Exception? = null
                for (m in completeMethods) {
                    try {
                        result = when (m.parameterTypes.size) {
                            4 -> m.invoke(null, handle, prompt, SYSTEM_PROMPT, 256)
                            3 -> m.invoke(null, handle, prompt, 256)
                            2 -> m.invoke(null, handle, prompt)
                            else -> continue
                        }
                        break
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }
                if (result == null) throw lastErr ?: Exception("complete not found")
                extractText(result).ifBlank { "پاسخی تولید نشد." }
            }
        } catch (e: Exception) {
            Log.e(TAG, "complete failed", e)
            "خطا در مدل آفلاین: ${e.message}"
        }
    }

    private fun extractText(result: Any): String {
        return try {
            val f = result.javaClass.getDeclaredField("text")
            f.isAccessible = true
            f.get(result)?.toString()?.trim().orEmpty()
        } catch (_: Exception) {
            try {
                result.javaClass.getMethod("getText").invoke(result)?.toString()?.trim().orEmpty()
            } catch (_: Exception) {
                result.toString()
            }
        }
    }

    fun release() {
        val h = modelHandle ?: return
        try {
            val llamaClass = Class.forName("dev.ffmpegkit.llama.Llama")
            llamaClass.methods.first { it.name == "releaseModel" }.invoke(null, h)
        } catch (_: Exception) {
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
