package com.mrfokolaii.aria.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // large file
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Progress(val downloaded: Long, val total: Long) {
        val percent: Int
            get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    fun targetFile(): File =
        File(
            context.getExternalFilesDir(OfflineLlmEngine.MODELS_DIR) ?: context.filesDir,
            OfflineLlmEngine.MODEL_FILE_NAME
        )

    suspend fun download(
        onProgress: (Progress) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val dest = targetFile()
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")

        try {
            val request = Request.Builder()
                .url(OfflineLlmEngine.MODEL_DOWNLOAD_URL)
                .header("User-Agent", "ARIA-Android")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("دانلود ناموفق: HTTP ${response.code}")
                    )
                }
                val body = response.body ?: return@withContext Result.failure(Exception("پاسخ خالی"))
                val total = body.contentLength().let { if (it > 0) it else 1_200_000_000L }
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(Progress(downloaded, total))
                        }
                        output.flush()
                    }
                }
            }

            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "Downloaded model: ${dest.length()} bytes")
            Result.success(dest)
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            try { tmp.delete() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "ModelDownloader"
    }
}
