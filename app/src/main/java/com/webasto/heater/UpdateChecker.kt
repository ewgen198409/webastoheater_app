package com.webasto.heater

import android.content.Context
import android.content.pm.PackageInfo
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
import kotlin.math.max
import kotlin.math.min

class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val githubApiUrl = "https://api.github.com/repos/ewgen198409/webastoheater_app/releases"

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String?,
        val currentVersion: String,
        val downloadUrl: String?,
        val releaseNotes: String?,
        val publishedAt: String?
    )

    interface UpdateCheckListener {
        fun onUpdateCheckStarted()
        fun onUpdateCheckCompleted(updateInfo: UpdateInfo)
        fun onUpdateCheckError(error: String)
    }

    fun checkForUpdates(listener: UpdateCheckListener) {
        val currentVersion = getCurrentVersion()

        listener.onUpdateCheckStarted()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val updateInfo = performUpdateCheck(currentVersion)
                Handler(Looper.getMainLooper()).post {
                    listener.onUpdateCheckCompleted(updateInfo)
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Error checking for updates", e)
                Handler(Looper.getMainLooper()).post {
                    listener.onUpdateCheckError("Ошибка проверки обновлений: ${e.message}")
                }
            }
        }
    }

    private fun performUpdateCheck(currentVersion: String): UpdateInfo {
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
            return UpdateInfo(
                hasUpdate = false,
                latestVersion = null,
                currentVersion = currentVersion,
                downloadUrl = null,
                releaseNotes = null,
                publishedAt = null
            )
        }

        // Получаем последний релиз (первый в массиве)
        val latestRelease = releases.getJSONObject(0)
        val latestVersion = extractVersionFromTag(latestRelease.getString("tag_name"))
        val hasUpdate = isNewerVersion(latestVersion, currentVersion)

        val downloadUrl = findApkDownloadUrl(latestRelease)
        val releaseNotes = latestRelease.optString("body", "")
        val publishedAt = latestRelease.optString("published_at", "")

        return UpdateInfo(
            hasUpdate = hasUpdate,
            latestVersion = latestVersion,
            currentVersion = currentVersion,
            downloadUrl = downloadUrl,
            releaseNotes = releaseNotes,
            publishedAt = publishedAt
        )
    }

    private fun getCurrentVersion(): String {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error getting current version", e)
            "1.0.0"
        }
    }

    private fun extractVersionFromTag(tagName: String): String {
        // Убираем префикс 'v' если он есть
        return tagName.removePrefix("v").removePrefix("V")
    }

    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        return try {
            val latestParts = latestVersion.split(".").map { it.toInt() }
            val currentParts = currentVersion.split(".").map { it.toInt() }

            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val latestPart = latestParts.getOrElse(i) { 0 }
                val currentPart = currentParts.getOrElse(i) { 0 }

                if (latestPart > currentPart) {
                    return true
                } else if (latestPart < currentPart) {
                    return false
                }
            }
            false
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error comparing versions", e)
            false
        }
    }

    private fun findApkDownloadUrl(release: JSONObject): String? {
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val assetName = asset.getString("name")
            val downloadUrl = asset.getString("browser_download_url")

            // Ищем APK файл
            if (assetName.endsWith(".apk", ignoreCase = true)) {
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
            Log.e("UpdateChecker", "Error formatting date", e)
            ""
        }
    }
}
