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
 * Auto-detects any .task / .gguf in models + Download folders.
 */
class OfflineLlmEngine(private val context: Context) {

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

    fun candidateTaskFiles(): List<File> {
        val names = listOf(TASK_FILE_NAME, TASK_FILE_NAME_ALT)
        val list = mutableListOf<File>()
        for (dir in modelSearchDirs()) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.filter {
                it.isFile && it.name.endsWith(".task", true) && it.length() > 50_000_000L
            }?.forEach { list.add(it) }
            for (name in names) list.add(File(dir, name))
        }
        return list.distinctBy { it.absolutePath }
    }

    fun candidateGgufFiles(): List<File> {
        val list = mutableListOf<File>()
        for (dir in modelSearchDirs()) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.filter {
                it.isFile && it.name.endsWith(".gguf", true) && it.length() > 50_000_000L
            }?.forEach { list.add(it) }
            list.add(File(dir, GGUF_FILE_NAME))
        }
        return list.distinctBy { it.absolutePath }
    }

    fun candidateModelFiles(): List<File> = candidateTaskFiles() + candidateGgufFiles()

    fun listDetectedModels(): List<String> =
        candidateModelFiles()
            .filter { it.exists() && it.length() > 50_000_000L }
            .map { "${it.name} (${it.length() / 1_000_000} MB)" }

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
        val detected = listDetectedModels()
        val detectedBlock = if (detected.isEmpty()) "" else "\nمدل‌های پیدا شده:\n" + detected.joinToString("\n")
        return when {
            activeBackend == Backend.MEDIAPIPE_GPU && task != null ->
                "موتور: MediaPipe GPU\n${task.name} (${task.length() / 1_000_000} MB)\n${task.absolutePath}$detectedBlock"
            activeBackend == Backend.MEDIAPIPE_CPU && task != null ->
                "موتور: MediaPipe (CPU)\n${task.name} (${task.length() / 1_000_000} MB)\n${task.absolutePath}$detectedBlock"
            activeBackend == Backend.LLAMA_CPU && gguf != null ->
                "موتور: GGUF CPU\n${gguf.name} (${gguf.length() / 1_000_000} MB)\n${gguf.absolutePath}$detectedBlock"
            task != null ->
                "مدل GPU آماده: ${task.name} (${task.length() / 1_000_000} MB)\n${task.absolutePath}\n(هنوز بارگذاری نشده)$detectedBlock"
            gguf != null ->
                "مدل CPU آماده: ${gguf.name} (${gguf.length() / 1_000_000} MB)\n${gguf.absolutePath}$detectedBlock"
            lastLoadError != null ->
                "خطا:\n$lastLoadError$detectedBlock"
            else ->
                "مدل پیدا نشد\nبرای GPU فایل .task را آپلود کن\nیا برای CPU فایل .gguf\nپوشه: Download یا models$detectedBlock"
        }
    }

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (sharedMediaPipe != null || sharedLlama != null) return@withContext true

            val taskFile = candidateTaskFiles()
                .filter { it.exists() && it.length() > 50_000_000L }
                .maxByOrNull { it.length() }
            if (taskFile != null) {
                try {
                    Log.i(TAG, "Loading MediaPipe model ${taskFile.absolutePath}")
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(taskFile.absolutePath)
                        .setMaxTokens(512)
                        .setMaxTopK(40)
                        .setPreferredBackend(LlmInference.Backend.GPU)
                        .build()
                    val engine = LlmInference.createFromOptions(context, options)
                    sharedMediaPipe = engine
                    activeBackend = Backend.MEDIAPIPE_GPU
                    lastLoadError = null
                    loadedPath = taskFile.absolutePath
                    Log.i(TAG, "MediaPipe loaded with preferredBackend=GPU")
                    return@withContext true
                } catch (e: Throwable) {
                    Log.e(TAG, "MediaPipe GPU load failed, trying DEFAULT", e)
                    try {
                        val options = LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(taskFile.absolutePath)
                            .setMaxTokens(512)
                            .setMaxTopK(40)
                            .setPreferredBackend(LlmInference.Backend.DEFAULT)
                            .build()
                        val engine = LlmInference.createFromOptions(context, options)
                        sharedMediaPipe = engine
                        activeBackend = Backend.MEDIAPIPE_CPU
                        lastLoadError = null
                        loadedPath = taskFile.absolutePath
                        Log.i(TAG, "MediaPipe loaded with DEFAULT backend")
                        return@withContext true
                    } catch (e2: Throwable) {
                        Log.e(TAG, "MediaPipe load failed, will try GGUF", e2)
                        lastLoadError = "MediaPipe: ${e2.javaClass.simpleName}: ${e2.message}"
                        sharedMediaPipe = null
                    }
                }
            }

            val gguf = candidateGgufFiles()
                .filter { it.exists() && it.length() > 50_000_000L }
                .maxByOrNull { it.length() }
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
                appendLine("لینک مدل GPU پیشنهادی:")
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

    enum class Backend { NONE, MEDIAPIPE_GPU, MEDIAPIPE_CPU, LLAMA_CPU }

    companion object {
        private const val TAG = "OfflineLlmEngine"
        const val MODELS_DIR = "models"

        const val TASK_FILE_NAME =
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        const val TASK_FILE_NAME_ALT = "qwen2.5-1.5b-instruct.task"
        const val TASK_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

        const val GGUF_FILE_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val MODEL_FILE_NAME = TASK_FILE_NAME
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

        fun unloadAll() {
            try { sharedMediaPipe?.close() } catch (_: Exception) {}
            sharedMediaPipe = null
            sharedLlama = null
            activeBackend = Backend.NONE
            loadedPath = null
            lastLoadError = null
        }

        private val SYSTEM_PROMPT =
            "تو آریا هستی، دستیار فارسی روی گوشی. همیشه به فارسی روان و طبیعی جواب بده. " +
                "جمله‌ها کوتاه، واضح و بدون انگلیسی اضافه. سلام بی‌مورد نگو. اگر ابزار اجرا شد نتیجه را ساده توضیح بده."
    }
}
