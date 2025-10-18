package com.webasto.heater

import android.app.DownloadManager as AndroidDownloadManager
import android.content.Context
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

class DownloadManager(private val context: Context) {

    private val androidDownloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as AndroidDownloadManager
    private var downloadReceiver: DownloadCompleteReceiver? = null
    private var currentDownloadId: Long = -1
    private var currentVersion: String = ""

    // BroadcastReceiver для отслеживания завершения скачивания
    private inner class DownloadCompleteReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == AndroidDownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val downloadId = intent.getLongExtra(AndroidDownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId == currentDownloadId) {
                    Log.d("DownloadManager", "Download completed: $downloadId")

                    // Получаем путь к скачанному файлу
                    val filePath = getDownloadedApkPath(context!!, currentVersion)

                    // Проверяем, что файл существует
                    val file = File(filePath)
                    if (file.exists()) {
                        Log.d("DownloadManager", "APK file exists, starting installation")
                        Toast.makeText(context, "Скачивание завершено. Запуск установки...", Toast.LENGTH_SHORT).show()

                        // Запускаем установку
                        installApk(filePath)

                        // Очищаем receiver после использования
                        cleanupReceiver()
                    } else {
                        Log.e("DownloadManager", "APK file not found at: $filePath")
                        Toast.makeText(context, "Ошибка: файл APK не найден после скачивания", Toast.LENGTH_LONG).show()
                        cleanupReceiver()
                    }
                }
            }
        }
    }

    private fun cleanupReceiver() {
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e("DownloadManager", "Error unregistering receiver", e)
            }
            downloadReceiver = null
        }
        currentDownloadId = -1
        currentVersion = ""
    }

    fun downloadApk(url: String, version: String, onComplete: (success: Boolean, filePath: String?) -> Unit) {
        try {
            // Очищаем предыдущий receiver если он был
            cleanupReceiver()

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

            // Регистрируем BroadcastReceiver для мониторинга завершения скачивания
            downloadReceiver = DownloadCompleteReceiver()
            val filter = IntentFilter(AndroidDownloadManager.ACTION_DOWNLOAD_COMPLETE)
            context.registerReceiver(downloadReceiver, filter)

            // Сохраняем информацию о текущем скачивании
            currentDownloadId = downloadId
            currentVersion = version

            Toast.makeText(context, "Скачивание начато. После завершения автоматически запустится установка.", Toast.LENGTH_LONG).show()

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
