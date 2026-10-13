package com.mrfokolaii.aria.agent

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device LLM:
 * 1) MediaPipe / LiteRT (.task) → GPU when available (free)
 * 2) llama-android GGUF → CPU fallback
 */
class OfflineLlmEngine(private val context: Context) {

    fun candidateTaskFiles(): List<File> {
        val names = listOf(TASK_FILE_NAME, TASK_FILE_NAME_ALT)
        val list = mutableListOf<File>()
        for (name in names) {
            context.getExternalFilesDir(MODELS_DIR)?.let { list.add(File(it, name)) }
            list.add(File(File(context.filesDir, MODELS_DIR), name))
            try {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                list.add(File(dl, name))
                list.add(File(File(dl, "Aria"), name))
            } catch (_: Exception) {}
            list.add(File("/storage/emulated/0/Download/$name"))
            list.add(File("/sdcard/Download/$name"))
        }
        return list.distinctBy { it.absolutePath }
    }

    fun candidateGgufFiles(): List<File> {
        val name = GGUF_FILE_NAME
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

    /** @deprecated use candidateTaskFiles / candidateGgufFiles */
    fun candidateModelFiles(): List<File> = candidateTaskFiles() + candidateGgufFiles()

    fun modelFile(): File =
        candidateTaskFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
            ?: candidateGgufFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
            ?: (context.getExternalFilesDir(MODELS_DIR)?.let { File(it, TASK_FILE_NAME) }
                ?: File(context.filesDir, "$MODELS_DIR/$TASK_FILE_NAME"))

    fun isModelPresent(): Boolean =
        candidateTaskFiles().any { it.exists() && it.length() > 50_000_000L } ||
            candidateGgufFiles().any { it.exists() && it.length() > 50_000_000L }

    fun modelStatusText(): String {
        val task = candidateTaskFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
        val gguf = candidateGgufFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
        return when {
            activeBackend == Backend.MEDIAPIPE_GPU && task != null ->
                "موتور: MediaPipe GPU\n${task.name} (${task.length() / 1_000_000} MB)\n${task.absolutePath}"
            activeBackend == Backend.MEDIAPIPE_CPU && task != null ->
                "موتور: MediaPipe (CPU)\n${task.name} (${task.length() / 1_000_000} MB)\n${task.absolutePath}"
            activeBackend == Backend.LLAMA_CPU && gguf != null ->
                "موتور: GGUF CPU\n${gguf.name} (${gguf.length() / 1_000_000} MB)\n${gguf.absolutePath}"
            task != null ->
                "مدل GPU آماده: ${task.name} (${task.length() / 1_000_000} MB)\n${task.absolutePath}\n(هنوز بارگذاری نشده)"
            gguf != null ->
                "مدل CPU آماده: ${gguf.name} (${gguf.length() / 1_000_000} MB)\n${gguf.absolutePath}"
            lastLoadError != null ->
                "خطا:\n$lastLoadError"
            else ->
                "مدل پیدا نشد\nبرای GPU فایل .task را آپلود کن\nیا برای CPU همان GGUF قبلی"
        }
    }

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (sharedMediaPipe != null || sharedLlama != null) return@withContext true

            // 1) Prefer MediaPipe .task (GPU-capable)
            val taskFile = candidateTaskFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
            if (taskFile != null) {
                try {
                    Log.i(TAG, "Loading MediaPipe model ${taskFile.absolutePath}")
                    val builder = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(taskFile.absolutePath)
                        .setMaxTokens(512)
                        .setMaxTopK(40)
                        .setTemperature(0.7f)

                    // Prefer GPU backend when API exposes it (free MediaPipe)
                    preferGpuBackend(builder)

                    val engine = LlmInference.createFromOptions(context, builder.build())
                    sharedMediaPipe = engine
                    activeBackend = Backend.MEDIAPIPE_GPU // preferred; actual may fall back inside MP
                    lastLoadError = null
                    loadedPath = taskFile.absolutePath
                    Log.i(TAG, "MediaPipe loaded (GPU preferred)")
                    return@withContext true
                } catch (e: Throwable) {
                    Log.e(TAG, "MediaPipe load failed, will try GGUF", e)
                    lastLoadError = "MediaPipe: ${e.javaClass.simpleName}: ${e.message}"
                    sharedMediaPipe = null
                }
            }

            // 2) GGUF CPU fallback
            val gguf = candidateGgufFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
            if (gguf == null) {
                if (lastLoadError == null) lastLoadError = "هیچ مدل .task یا .gguf پیدا نشد"
                return@withContext false
            }
            try {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                Log.i(TAG, "Loading GGUF ${gguf.absolutePath} threads=$threads")
                val config = LlamaConfig(contextSize = 1024, threads = threads)
                val model = Llama.loadModel(modelPath = gguf.absolutePath, config = config)
                sharedLlama = model
                activeBackend = Backend.LLAMA_CPU
                lastLoadError = null
                loadedPath = gguf.absolutePath
                Log.i(TAG, "GGUF CPU model loaded")
                true
            } catch (e: Throwable) {
                lastLoadError = "GGUF: ${e.javaClass.simpleName}: ${e.message}"
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
                appendLine("برای GPU: فایل .task را از تنظیمات آپلود کن")
                appendLine("لینک مدل GPU:")
                appendLine(TASK_DOWNLOAD_URL)
            }
        }

        val prompt = buildString {
            history.takeLast(3).forEach { (role, content) ->
                if (role == "user") append("کاربر: ${content.take(200)}\n")
                else append("آریا: ${content.take(200)}\n")
            }
            append("کاربر: ${userMessage.take(500)}\nآریا:")
        }

        val fullPrompt = "$SYSTEM_PROMPT\n\n$prompt"

        // MediaPipe path
        sharedMediaPipe?.let { engine ->
            return@withContext try {
                loadMutex.withLock {
                    val result = engine.generateResponse(fullPrompt)
                    result.trim().ifBlank { "پاسخی تولید نشد." }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaPipe generate failed", e)
                "خطا در GPU/MediaPipe: ${e.message}"
            }
        }

        // GGUF path
        val model = sharedLlama ?: return@withContext "مدل null است."
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

    private fun preferGpuBackend(builder: LlmInference.LlmInferenceOptions.Builder) {
        try {
            val backendClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInference\$Backend"
            )
            @Suppress("UNCHECKED_CAST")
            val gpu = java.lang.Enum.valueOf(backendClass as Class<out Enum<*>>, "GPU")
            val method = builder.javaClass.methods.firstOrNull { m ->
                m.name == "setPreferredBackend" && m.parameterTypes.size == 1
            }
            if (method != null) {
                method.invoke(builder, gpu)
                Log.i(TAG, "setPreferredBackend(GPU) applied")
            } else {
                Log.i(TAG, "setPreferredBackend not in this MediaPipe version; model may still use GPU")
            }
        } catch (e: Throwable) {
            Log.i(TAG, "GPU preference optional: ${e.message}")
        }
    }

    enum class Backend { NONE, MEDIAPIPE_GPU, MEDIAPIPE_CPU, LLAMA_CPU }

    companion object {
        private const val TAG = "OfflineLlmEngine"
        const val MODELS_DIR = "models"

        /** MediaPipe / LiteRT GPU-ready Qwen 1.5B */
        const val TASK_FILE_NAME =
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        const val TASK_FILE_NAME_ALT = "qwen2.5-1.5b-instruct.task"
        const val TASK_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

        /** Previous GGUF CPU model */
        const val GGUF_FILE_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val MODEL_FILE_NAME = TASK_FILE_NAME // for ModelImportUtil default dest
        const val MODEL_DOWNLOAD_URL = TASK_DOWNLOAD_URL

        private val loadMutex = Mutex()
        @Volatile var sharedMediaPipe: LlmInference? = null
            private set
        @Volatile var sharedLlama: LlamaModel? = null
            private set
        @Volatile var activeBackend: Backend = Backend.NONE
            private set
        @Volatile var lastLoadError: String? = null
            private set
        @Volatile var loadedPath: String? = null
            private set

        private val SYSTEM_PROMPT =
            "تو آریا هستی. فقط فارسی، خیلی کوتاه و مستقیم جواب بده. سلام اضافه نکن مگر کاربر سلام کند."
    }
}
