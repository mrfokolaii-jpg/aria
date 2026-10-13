package com.mrfokolaii.aria.agent

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object ModelImportUtil {

    fun appModelFile(context: Context): File {
        val dir = context.getExternalFilesDir(OfflineLlmEngine.MODELS_DIR)
            ?: File(context.filesDir, OfflineLlmEngine.MODELS_DIR).also { it.mkdirs() }
        if (!dir.exists()) dir.mkdirs()
        return File(dir, OfflineLlmEngine.MODEL_FILE_NAME)
    }

    fun status(context: Context): String {
        val engine = OfflineLlmEngine(context)
        val found = engine.candidateModelFiles().firstOrNull { it.exists() && it.length() > 50_000_000L }
        return if (found != null) {
            "مدل آماده: ${found.name} (${found.length() / 1_000_000} MB)\n${found.absolutePath}"
        } else {
            "مدل پیدا نشد.\nاز دکمه «انتخاب فایل مدل» همان فایل داخل Download را انتخاب کن تا کپی شود."
        }
    }

    /** Copy content URI (from file picker) into app-private models folder. */
    fun importFromUri(context: Context, uri: Uri): Result<File> {
        return try {
            val dest = appModelFile(context)
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
                return Result.failure(Exception("فایل خیلی کوچک است (${tmp.length()} بایت) — فایل کامل GGUF را انتخاب کن"))
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "Imported model to ${dest.absolutePath} (${dest.length()})")
            Result.success(dest)
        } catch (e: Exception) {
            Log.e(TAG, "import failed", e)
            Result.failure(e)
        }
    }

    private const val TAG = "ModelImportUtil"
}
