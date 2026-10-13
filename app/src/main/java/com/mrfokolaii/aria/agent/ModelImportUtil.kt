package com.mrfokolaii.aria.agent

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object ModelImportUtil {

    fun appModelFile(context: Context, fileName: String = OfflineLlmEngine.MODEL_FILE_NAME): File {
        val dir = context.getExternalFilesDir(OfflineLlmEngine.MODELS_DIR)
            ?: File(context.filesDir, OfflineLlmEngine.MODELS_DIR).also { it.mkdirs() }
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName)
    }

    fun status(context: Context): String = OfflineLlmEngine(context).modelStatusText()

    /** Copy content URI into app models folder (.task for GPU or .gguf for CPU). */
    fun importFromUri(context: Context, uri: Uri): Result<File> {
        return try {
            val segment = (uri.lastPathSegment ?: "model").lowercase()
            val cleanName = when {
                segment.endsWith(".gguf") || segment.contains("gguf") ->
                    OfflineLlmEngine.GGUF_FILE_NAME
                else -> OfflineLlmEngine.TASK_FILE_NAME
            }
            val dest = appModelFile(context, cleanName)
            val tmp = File(dest.absolutePath + ".part")
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return Result.failure(Exception("نتوانستم فایل را باز کنم"))
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
            if (tmp.length() < 50_000_000L) {
                tmp.delete()
                return Result.failure(
                    Exception("فایل خیلی کوچک است (${tmp.length()} بایت) — مدل کامل را انتخاب کن")
                )
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            OfflineLlmEngine.unloadAll()
            Log.i(TAG, "Imported model to ${dest.absolutePath} (${dest.length()})")
            Result.success(dest)
        } catch (e: Exception) {
            Log.e(TAG, "import failed", e)
            Result.failure(e)
        }
    }

    private const val TAG = "ModelImportUtil"
}
