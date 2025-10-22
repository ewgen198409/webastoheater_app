package com.webasto.heater

import android.app.DownloadManager as AndroidDownloadManager
import android.content.Context
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.appcompat.app.AlertDialog
import android.view.LayoutInflater
import android.widget.TextView
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
                Log.d("DownloadManager", "Download completed broadcast received. DownloadId: $downloadId, CurrentId: $currentDownloadId")

                if (downloadId == currentDownloadId) {
                    Log.d("DownloadManager", "Download completed: $downloadId")

                    // Проверяем статус скачивания через DownloadManager
                    val query = android.app.DownloadManager.Query().setFilterById(downloadId)
                    val cursor = androidDownloadManager.query(query)

                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
                        val status = cursor.getInt(statusIndex)

                        Log.d("DownloadManager", "Download status: $status")

                        if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                            Log.d("DownloadManager", "Download successful, using alternative file path method")

                            try {
                                // Используем альтернативный способ получения пути к файлу
                                val filePath = getDownloadedApkPath(context ?: return, currentVersion)
                                val file = File(filePath)

                                if (file.exists() && file.length() > 0) {
                                    Log.d("DownloadManager", "APK file exists at: $filePath, size: ${file.length()}")

                                    // Показываем уведомление вместо автоматической установки
                                    showInstallationNotification(filePath, currentVersion)

                                    // Очищаем receiver после использования
                                    cleanupReceiver()
                                } else {
                                    Log.e("DownloadManager", "APK file not found or empty at: $filePath")
                                    Toast.makeText(context, "Ошибка: файл APK не найден или пустой после скачивания", Toast.LENGTH_LONG).show()
                                    cleanupReceiver()
                                }
                            } catch (e: Exception) {
                                Log.e("DownloadManager", "Error accessing downloaded file", e)
                                Toast.makeText(context, "Ошибка доступа к файлу: ${e.message}", Toast.LENGTH_LONG).show()
                                cleanupReceiver()
                            }
                        } else {
                            Log.e("DownloadManager", "Download failed with status: $status")
                            Toast.makeText(context, "Ошибка скачивания: статус $status", Toast.LENGTH_LONG).show()
                            cleanupReceiver()
                        }
                    } else {
                        Log.e("DownloadManager", "Could not find download with ID: $downloadId")
                        Toast.makeText(context, "Ошибка: не найден файл скачивания", Toast.LENGTH_LONG).show()
                        cleanupReceiver()
                    }
                    cursor.close()
                } else {
                    Log.d("DownloadManager", "Download ID mismatch: received $downloadId, expected $currentDownloadId")
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
                // Показываем уведомление о завершении скачивания
                setNotificationVisibility(AndroidDownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // Сохраняем в публичную папку Downloads для надежного доступа
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "WebastoHeater-$version.apk")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                // Разрешаем установку APK из неизвестных источников для Android 8+
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    setRequiresCharging(false)
                    setRequiresDeviceIdle(false)
                }
                // Добавляем MIME тип для APK файлов
                setMimeType("application/vnd.android.package-archive")
            }

            // Запускаем скачивание
            val downloadId = androidDownloadManager.enqueue(request)

            Log.d("DownloadManager", "Started download with ID: $downloadId")

            // Регистрируем BroadcastReceiver для мониторинга завершения скачивания
            downloadReceiver = DownloadCompleteReceiver()
            val filter = IntentFilter(AndroidDownloadManager.ACTION_DOWNLOAD_COMPLETE)
            androidx.core.content.ContextCompat.registerReceiver(context, downloadReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

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
                Log.e("DownloadManager", "APK file does not exist at: $filePath")
                Toast.makeText(context, "Файл APK не найден: $filePath", Toast.LENGTH_SHORT).show()
                return
            }

            Log.d("DownloadManager", "Installing APK from: $filePath, size: ${file.length()}")

            val uri = getUriForFile(file)

            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(intent)
                Log.d("DownloadManager", "Installation intent started successfully")

            } catch (e: Exception) {
                Log.e("DownloadManager", "Failed to start installation activity", e)
                Toast.makeText(context, "Не удалось запустить установку: ${e.message}", Toast.LENGTH_LONG).show()

                // Fallback installation also failed - simplified
                Toast.makeText(context, "Ошибка установки. Попробуйте установить файл вручную из Downloads", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            Log.e("DownloadManager", "Error installing APK", e)
            Toast.makeText(context, "Ошибка при установке: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getUriForFile(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun showInstallationNotification(filePath: String, version: String) {
        try {
            Log.d("DownloadManager", "Showing installation dialog for APK: $filePath")

            // Показываем диалог с информацией об установке
            Handler(Looper.getMainLooper()).post {
                try {
                    val dialogView = LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, null)
                    val text1 = dialogView.findViewById<TextView>(android.R.id.text1)
                    val text2 = dialogView.findViewById<TextView>(android.R.id.text2)

                    text1.text = "Обновление загружено"
                    text2.text = "Webasto Heater v$version готов к установке"

                    AlertDialog.Builder(context)
                        .setView(dialogView)
                        .setTitle("Установка обновления")
                        .setMessage("Файл обновления успешно загружен. Нажмите 'Установить' для запуска установки.")
                        .setPositiveButton("Установить") { _, _ ->
                            try {
                                installApk(filePath)
                                Toast.makeText(context, "Запуск установки...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Log.e("DownloadManager", "Error starting installation from dialog", e)
                                Toast.makeText(context, "Ошибка при установке: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("Позже") { dialog, _ ->
                            dialog.dismiss()
                            Toast.makeText(context, "Файл сохранен в Downloads. Вы можете установить его позже.", Toast.LENGTH_LONG).show()
                        }
                        .setCancelable(true)
                        .show()

                } catch (e: Exception) {
                    Log.e("DownloadManager", "Error showing installation dialog", e)
                    // Если не удается показать диалог, пробуем установить напрямую
                    installApk(filePath)
                }
            }

        } catch (e: Exception) {
            Log.e("DownloadManager", "Error in showInstallationNotification", e)
            Toast.makeText(context, "Ошибка при подготовке установки: ${e.message}", Toast.LENGTH_LONG).show()
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
