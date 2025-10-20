package com.webasto.heater

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class FirmwareUpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val githubApiUrl = "https://api.github.com/repos/ewgen198409/esp8266_Webasto/releases"

    data class FirmwareUpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String?,
        val currentVersion: String,
        val downloadUrl: String?,
        val releaseNotes: String?,
        val publishedAt: String?
    )

    interface FirmwareUpdateCheckListener {
        fun onFirmwareUpdateCheckStarted()
        fun onFirmwareUpdateCheckCompleted(updateInfo: FirmwareUpdateInfo)
        fun onFirmwareUpdateCheckError(error: String)
    }

    fun checkForFirmwareUpdates(listener: FirmwareUpdateCheckListener) {
        listener.onFirmwareUpdateCheckStarted()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val updateInfo = performFirmwareUpdateCheck()
                Handler(Looper.getMainLooper()).post {
                    listener.onFirmwareUpdateCheckCompleted(updateInfo)
                }
            } catch (e: Exception) {
                Log.e("FirmwareUpdateChecker", "Error checking for firmware updates", e)
                Handler(Looper.getMainLooper()).post {
                    listener.onFirmwareUpdateCheckError("Ошибка проверки обновлений firmware: ${e.message}")
                }
            }
        }
    }

    private fun performFirmwareUpdateCheck(): FirmwareUpdateInfo {
        val request = Request.Builder()
            .url(githubApiUrl)
            .addHeader("User-Agent", "WebastoHeater-App")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response")

        val releases = JSONArray(responseBody as String)

        if (releases.length() == 0) {
            return FirmwareUpdateInfo(
                hasUpdate = false,
                latestVersion = null,
                currentVersion = "unknown",
                downloadUrl = null,
                releaseNotes = null,
                publishedAt = null
            )
        }

        // Получаем последний релиз (первый в массиве)
        val latestRelease = releases.getJSONObject(0)
        val latestVersion = extractVersionFromTag(latestRelease.getString("tag_name"))

        // Для firmware всегда считаем, что есть обновление, если релиз найден
        // В будущем можно добавить получение текущей версии с устройства
        val hasUpdate = true

        val downloadUrl = findFirmwareDownloadUrl(latestRelease)
        val releaseNotes = latestRelease.optString("body", "")
        val publishedAt = latestRelease.optString("published_at", "")

        return FirmwareUpdateInfo(
            hasUpdate = hasUpdate,
            latestVersion = latestVersion,
            currentVersion = "unknown",
            downloadUrl = downloadUrl,
            releaseNotes = releaseNotes,
            publishedAt = publishedAt
        )
    }

    private fun extractVersionFromTag(tagName: String): String {
        // Убираем префикс 'v' если он есть
        return tagName.removePrefix("v").removePrefix("V")
    }

    private fun findFirmwareDownloadUrl(release: JSONObject): String? {
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val assetName = asset.getString("name")
            val downloadUrl = asset.getString("browser_download_url")

            // Ищем .bin файл для ESP8266
            if (assetName.endsWith(".bin", ignoreCase = true)) {
                return downloadUrl
            }
        }
        return null
    }

    fun formatReleaseDate(dateString: String?): String {
        if (dateString.isNullOrEmpty()) return ""

        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val outputFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            val date = inputFormat.parse(dateString) ?: return ""
            outputFormat.format(date)
        } catch (e: Exception) {
            Log.e("FirmwareUpdateChecker", "Error formatting date", e)
            ""
        }
    }
}
