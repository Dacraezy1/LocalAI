package com.localai.model

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HuggingFace Hub API client for browsing GGUF models.
 * Uses the public /api/models endpoint — no auth needed for public models.
 */
object HuggingFaceApi {

    private const val TAG = "HuggingFaceApi"
    private const val BASE = "https://huggingface.co/api"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ── Data classes ─────────────────────────────────────────────────────────

    data class HFModel(
        @SerializedName("id")          val id: String = "",
        @SerializedName("modelId")     val modelId: String = "",
        @SerializedName("likes")       val likes: Int = 0,
        @SerializedName("downloads")   val downloads: Int = 0,
        @SerializedName("tags")        val tags: List<String> = emptyList(),
        @SerializedName("cardData")    val cardData: CardData? = null,
        @SerializedName("siblings")    val siblings: List<Sibling> = emptyList()
    ) {
        val repoId: String get() = id.ifBlank { modelId }
        val ggufFiles: List<Sibling> get() = siblings.filter {
            it.rfilename.endsWith(".gguf", ignoreCase = true)
        }
    }

    data class CardData(
        @SerializedName("license") val license: String? = null
    )

    data class Sibling(
        @SerializedName("rfilename") val rfilename: String = "",
        @SerializedName("size")      val size: Long = 0L
    )

    data class SearchResult(
        val models: List<HFModel>,
        val error: String? = null
    )

    data class FilesResult(
        val files: List<GgufFile>,
        val error: String? = null
    )

    data class GgufFile(
        val filename: String,
        val sizeBytes: Long,
        val downloadUrl: String,
        val repoId: String,
        val sizeLabel: String = formatSize(sizeBytes)
    )

    // ── API calls ────────────────────────────────────────────────────────────

    /**
     * Search HuggingFace for GGUF models.
     * Filters to gguf tag, sorted by downloads.
     */
    suspend fun searchModels(query: String, limit: Int = 20): SearchResult =
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                val url = "$BASE/models?search=$encodedQuery&filter=gguf&sort=downloads&direction=-1&limit=$limit"
                val req = Request.Builder().url(url)
                    .addHeader("Accept", "application/json")
                    .build()

                val body = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext SearchResult(emptyList(), "HTTP ${resp.code}")
                    resp.body?.string() ?: return@withContext SearchResult(emptyList(), "Empty response")
                }

                val models = gson.fromJson(body, Array<HFModel>::class.java).toList()
                SearchResult(models)
            } catch (e: Exception) {
                Log.e(TAG, "searchModels error: ${e.message}")
                SearchResult(emptyList(), e.message)
            }
        }

    /**
     * Get all GGUF files for a specific repo.
     */
    suspend fun getGgufFiles(repoId: String): FilesResult =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE/models/$repoId"
                val req = Request.Builder().url(url)
                    .addHeader("Accept", "application/json")
                    .build()

                val body = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext FilesResult(emptyList(), "HTTP ${resp.code}")
                    resp.body?.string() ?: return@withContext FilesResult(emptyList(), "Empty response")
                }

                val model = gson.fromJson(body, HFModel::class.java)
                val files = model.ggufFiles.map { sibling ->
                    GgufFile(
                        filename    = sibling.rfilename,
                        sizeBytes   = sibling.size,
                        downloadUrl = "https://huggingface.co/$repoId/resolve/main/${sibling.rfilename}",
                        repoId      = repoId
                    )
                }
                FilesResult(files)
            } catch (e: Exception) {
                Log.e(TAG, "getGgufFiles error: ${e.message}")
                FilesResult(emptyList(), e.message)
            }
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    fun formatSize(bytes: Long): String = when {
        bytes <= 0   -> "?"
        bytes < 1024 * 1024 * 1024 -> "%.0f MB".format(bytes / 1e6)
        else -> "%.1f GB".format(bytes / 1e9)
    }

    fun formatDownloads(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
        n >= 1_000     -> "%.1fK".format(n / 1_000f)
        else           -> n.toString()
    }
}
