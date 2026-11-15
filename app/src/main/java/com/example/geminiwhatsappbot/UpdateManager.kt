package com.example.geminiwhatsappbot

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val TAG = "UpdateManager"
    const val CURRENT_VERSION = "v1.3.0"
    private const val REPO_OWNER = "shashankswaroop789-eng"
    private const val REPO_NAME = "gemini-whatsapp-bot"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getInstalledVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = pInfo.versionName
            if (name.isNullOrBlank()) CURRENT_VERSION else if (name.startsWith("v")) name else "v$name"
        } catch (e: Exception) {
            CURRENT_VERSION
        }
    }

    fun checkForUpdate(context: Context, isManualCheck: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        val currentVersion = getInstalledVersion(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(GITHUB_API_URL)
                    .addHeader("User-Agent", "GeminiWhatsAppBot-Updater")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        if (isManualCheck) {
                            Toast.makeText(context, "You are using the latest version ($currentVersion)", Toast.LENGTH_SHORT).show()
                        }
                        onComplete?.invoke(false)
                    }
                    return@launch
                }

                val body = response.body?.string() ?: return@launch
                val json = JSONObject(body)
                val latestTag = json.optString("tag_name", "")
                val releaseNotes = json.optString("body", "Bug fixes and improvements.")

                var apkDownloadUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            apkDownloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                val isNewer = isVersionNewer(latestTag, currentVersion)

                withContext(Dispatchers.Main) {
                    if (isNewer && apkDownloadUrl.isNotBlank()) {
                        showUpdateDialog(context, latestTag, releaseNotes, apkDownloadUrl)
                        onComplete?.invoke(true)
                    } else {
                        if (isManualCheck) {
                            Toast.makeText(context, "App is up to date ($currentVersion) ✓", Toast.LENGTH_SHORT).show()
                        }
                        onComplete?.invoke(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                withContext(Dispatchers.Main) {
                    if (isManualCheck) {
                        Toast.makeText(context, "App is up to date ($currentVersion)", Toast.LENGTH_SHORT).show()
                    }
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun isVersionNewer(latest: String, current: String): Boolean {
        return try {
            val lParts = latest.removePrefix("v").trim().split(".").map { it.toIntOrNull() ?: 0 }
            val cParts = current.removePrefix("v").trim().split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(lParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val l = if (i < lParts.size) lParts[i] else 0
                val c = if (i < cParts.size) cParts[i] else 0
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun showUpdateDialog(context: Context, newVersion: String, notes: String, downloadUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("🚀 Update Available: $newVersion")
            .setMessage("What's new:\n\n$notes\n\nWould you like to download and install this update now?")
            .setPositiveButton("Update Now") { _, _ ->
                downloadAndInstallApk(context, downloadUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstallApk(context: Context, url: String) {
        Toast.makeText(context, "Downloading update in background...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body ?: return@launch

                val destFile = File(context.cacheDir, "update.apk")
                val fos = FileOutputStream(destFile)
                body.byteStream().use { input ->
                    fos.use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    installApk(context, destFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download update APK", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Update download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
