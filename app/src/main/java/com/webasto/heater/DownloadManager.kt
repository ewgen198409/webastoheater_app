package com.webasto.heater

import android.app.DownloadManager as AndroidDownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class DownloadManager(private val context: Context) {

    private val androidDownloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as AndroidDownloadManager

    fun downloadApk(url: String, version: String, onComplete: (success: Boolean, filePath: String?) -> Unit) {
        try {
            // Создаем URI для URL
            val uri = Uri.parse(url)

            // Создаем запрос на скачивание
            val request = AndroidDownloadManager.Request(uri).apply {
                setTitle("Скачивание обновления Webasto Heater v$version")
                setDescription("Загрузка APK файла обновления")
                setNotificationVisibility(AndroidDownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "WebastoHeater-$version.apk")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            // Запускаем скачивание
            val downloadId = androidDownloadManager.enqueue(request)

            Log.d("DownloadManager", "Started download with ID: $downloadId")

            // Здесь можно добавить мониторинг скачивания
            // Для простоты пока просто показываем уведомление
            Toast.makeText(context, "Скачивание начато. Проверьте уведомления.", Toast.LENGTH_LONG).show()

            onComplete(true, null)

        } catch (e: Exception) {
            Log.e("DownloadManager", "Error downloading APK", e)
            Toast.makeText(context, "Ошибка при скачивании: ${e.message}", Toast.LENGTH_LONG).show()
            onComplete(false, null)
        }
    }

    fun installApk(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "Файл APK не найден", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

        } catch (e: Exception) {
            Log.e("DownloadManager", "Error installing APK", e)
            Toast.makeText(context, "Ошибка при установке: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        fun getDownloadedApkPath(context: Context, version: String): String {
            return File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "WebastoHeater-$version.apk"
            ).absolutePath
        }
    }
}
