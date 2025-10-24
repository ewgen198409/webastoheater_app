package com.webasto.heater

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class FirmwareDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    interface DownloadListener {
        fun onDownloadStarted()
        fun onDownloadProgress(progress: Int) // Прогресс в процентах (0-100)
        fun onDownloadCompleted(filePath: String)
        fun onDownloadError(error: String)
    }

    suspend fun downloadFirmware(url: String, fileName: String, listener: DownloadListener) {
        listener.onDownloadStarted()
        val file = File(context.cacheDir, fileName) // Сохраняем во временную папку кэша

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw IOException("Failed to download file: HTTP ${response.code}")
                }

                val body = response.body ?: throw IOException("Response body is empty")
                val contentLength = body.contentLength()

                if (contentLength == 0L) {
                    throw IOException("Downloaded file is empty")
                }

                var bytesCopied: Long = 0
                body.byteStream().use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        val buffer = ByteArray(4096) // 4KB buffer
                        var bytes = inputStream.read(buffer)
                        while (bytes >= 0) {
                            outputStream.write(buffer, 0, bytes)
                            bytesCopied += bytes
                            val progress = ((bytesCopied * 100) / contentLength).toInt()
                            listener.onDownloadProgress(progress)
                            bytes = inputStream.read(buffer)
                        }
                    }
                }

                if (bytesCopied != contentLength) {
                    throw IOException("Download incomplete: expected $contentLength bytes, got $bytesCopied")
                }

                listener.onDownloadCompleted(file.absolutePath)
                Log.d("FirmwareDownloader", "Firmware downloaded to: ${file.absolutePath}")

            } catch (e: Exception) {
                Log.e("FirmwareDownloader", "Error downloading firmware", e)
                listener.onDownloadError("Ошибка загрузки прошивки: ${e.message}")
                // Удаляем неполный файл в случае ошибки
                if (file.exists()) {
                    file.delete()
                }
                return@withContext // Добавляем явный return для withContext
            }
        }
    }
}
