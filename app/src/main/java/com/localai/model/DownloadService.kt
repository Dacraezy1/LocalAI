package com.localai.model

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.localai.ui.ModelManagerActivity
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class DownloadService : Service() {

    companion object {
        const val ACTION_DOWNLOAD   = "com.localai.DOWNLOAD"
        const val ACTION_CANCEL     = "com.localai.CANCEL"
        const val EXTRA_URL         = "url"
        const val EXTRA_FILE_NAME   = "file_name"
        const val EXTRA_MODEL_ID    = "model_id"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val CHANNEL_ID        = "download_channel"
        const val NOTIF_ID          = 1001

        const val BROADCAST_PROGRESS    = "com.localai.DOWNLOAD_PROGRESS"
        const val BROADCAST_DONE        = "com.localai.DOWNLOAD_DONE"
        const val BROADCAST_ERROR       = "com.localai.DOWNLOAD_ERROR"
        const val EXTRA_PROGRESS        = "progress"
        const val EXTRA_SPEED_KBPS      = "speed_kbps"
        const val EXTRA_ETA_SECS        = "eta_secs"
        const val EXTRA_DOWNLOADED_BYTES = "downloaded_bytes"
        const val EXTRA_TOTAL_BYTES     = "total_bytes"
        const val EXTRA_DOWNLOADED_PATH = "downloaded_path"
        const val EXTRA_ERROR_MSG       = "error_msg"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentCall: Call? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0,  java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val url         = intent.getStringExtra(EXTRA_URL)         ?: return START_NOT_STICKY
                val fileName    = intent.getStringExtra(EXTRA_FILE_NAME)   ?: return START_NOT_STICKY
                val modelId     = intent.getStringExtra(EXTRA_MODEL_ID)    ?: ""
                val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: fileName
                startForeground(NOTIF_ID, buildNotification(displayName, "Starting…", 0))
                scope.launch { download(url, fileName, modelId, displayName) }
            }
            ACTION_CANCEL -> {
                currentCall?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun download(url: String, fileName: String, modelId: String, displayName: String) {
        val dir      = getExternalFilesDir("models") ?: filesDir.resolve("models").also { it.mkdirs() }
        val outFile  = File(dir, fileName)
        val tempFile = File(dir, "$fileName.tmp")

        // Resume support
        val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L

        val request = Request.Builder()
            .url(url)
            .apply { if (resumeFrom > 0) addHeader("Range", "bytes=$resumeFrom-") }
            .build()

        try {
            val response = withContext(Dispatchers.IO) {
                val call = client.newCall(request)
                currentCall = call
                call.execute()
            }

            if (!response.isSuccessful && response.code != 206) {
                broadcastError("HTTP ${response.code}: ${response.message}")
                stopSelf()
                return
            }

            val body = response.body ?: run { broadcastError("Empty body"); stopSelf(); return }
            val contentLength = body.contentLength()
            // totalBytes = already-downloaded + remaining content
            val totalBytes = if (contentLength > 0) resumeFrom + contentLength else -1L

            withContext(Dispatchers.IO) {
                FileOutputStream(tempFile, resumeFrom > 0).use { out ->
                    val buffer     = ByteArray(32 * 1024)  // 32KB chunks
                    var downloaded = resumeFrom
                    var read: Int

                    // Speed calculation state — only updated every 1 second
                    var windowStart = System.currentTimeMillis()
                    var windowBytes = downloaded

                    body.byteStream().use { input ->
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            val elapsed = now - windowStart

                            if (elapsed >= 1000) {   // update at most once per second
                                val bytesInWindow = downloaded - windowBytes
                                val speedKbps = (bytesInWindow * 1000L / elapsed / 1024L)
                                    .coerceAtLeast(0L)

                                val progress = if (totalBytes > 0)
                                    ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                else -1

                                val etaSecs = if (totalBytes > 0 && speedKbps > 0)
                                    ((totalBytes - downloaded) / 1024L / speedKbps).toInt()
                                else -1

                                windowStart = now
                                windowBytes = downloaded

                                broadcastProgress(progress, speedKbps, etaSecs, downloaded, totalBytes)
                                updateNotification(displayName, progress, speedKbps)
                            }
                        }
                    }
                }
            }

            // Finalize
            if (!tempFile.renameTo(outFile)) {
                // renameTo can fail across filesystems — copy then delete
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }

            broadcastProgress(100, 0, 0, outFile.length(), outFile.length())
            broadcastDone(outFile.absolutePath, modelId)
            updateNotification(displayName, 100, 0)

        } catch (e: IOException) {
            if (e.message?.contains("cancel", ignoreCase = true) == true ||
                e.message?.contains("Socket closed", ignoreCase = true) == true) {
                Log.i("DownloadService", "Download cancelled by user")
            } else {
                Log.e("DownloadService", "Download error: ${e.message}")
                broadcastError(e.message ?: "IO error")
            }
        } finally {
            stopSelf()
        }
    }

    private fun broadcastProgress(progress: Int, speedKbps: Long, etaSecs: Int,
                                   downloadedBytes: Long, totalBytes: Long) {
        sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_SPEED_KBPS, speedKbps)
            putExtra(EXTRA_ETA_SECS, etaSecs)
            putExtra(EXTRA_DOWNLOADED_BYTES, downloadedBytes)
            putExtra(EXTRA_TOTAL_BYTES, totalBytes)
        })
    }

    private fun broadcastDone(path: String, modelId: String) {
        sendBroadcast(Intent(BROADCAST_DONE).apply {
            putExtra(EXTRA_DOWNLOADED_PATH, path)
            putExtra(EXTRA_MODEL_ID, modelId)
        })
    }

    private fun broadcastError(msg: String) {
        sendBroadcast(Intent(BROADCAST_ERROR).apply { putExtra(EXTRA_ERROR_MSG, msg) })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Model Downloads",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "GGUF model download progress"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(name: String, text: String, progress: Int): Notification {
        val cancelPi = PendingIntent.getService(this, 0,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 0,
            Intent(this, ModelManagerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading: $name")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress < 0)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelPi)
            .build()
    }

    private fun updateNotification(name: String, progress: Int, speedKbps: Long) {
        val text = when {
            progress < 0  -> "Connecting…"
            progress >= 100 -> "Download complete"
            speedKbps > 0 -> "$progress%  •  ${HuggingFaceApi.formatSize(speedKbps * 1024)}/s"
            else          -> "$progress%"
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(name, text, progress))
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
