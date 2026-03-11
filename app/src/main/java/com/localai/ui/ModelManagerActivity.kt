package com.localai.ui

import android.content.*
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.localai.databinding.ActivityModelManagerBinding
import com.localai.model.*
import kotlinx.coroutines.launch
import java.io.File

class ModelManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelManagerBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private lateinit var catalogAdapter: CatalogAdapter
    private lateinit var installedAdapter: InstalledModelAdapter

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                DownloadService.BROADCAST_PROGRESS -> {
                    val progress   = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, -1)
                    val speedKbps  = intent.getLongExtra(DownloadService.EXTRA_SPEED_KBPS, 0)
                    val etaSecs    = intent.getIntExtra(DownloadService.EXTRA_ETA_SECS, -1)
                    catalogAdapter.updateDownloadProgress(progress, speedKbps, etaSecs)
                }
                DownloadService.BROADCAST_DONE -> {
                    val path    = intent.getStringExtra(DownloadService.EXTRA_DOWNLOADED_PATH) ?: return
                    val modelId = intent.getStringExtra(DownloadService.EXTRA_MODEL_ID) ?: return
                    onDownloadComplete(path, modelId)
                }
                DownloadService.BROADCAST_ERROR -> {
                    val msg = intent.getStringExtra(DownloadService.EXTRA_ERROR_MSG) ?: "Unknown"
                    catalogAdapter.clearDownloadState()
                    Toast.makeText(this@ModelManagerActivity, "Download failed: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Models"

        setupCatalog()
        setupInstalled()
        loadInstalledModels()
    }

    private fun setupCatalog() {
        catalogAdapter = CatalogAdapter { entry ->
            startDownload(entry)
        }
        binding.rvCatalog.apply {
            adapter = catalogAdapter
            layoutManager = LinearLayoutManager(this@ModelManagerActivity)
        }
        catalogAdapter.submitList(ModelCatalog.models)
    }

    private fun setupInstalled() {
        installedAdapter = InstalledModelAdapter(
            onActivate = { model ->
                lifecycleScope.launch {
                    db.modelDao().clearActive()
                    db.modelDao().setActive(model.id)
                    Toast.makeText(this@ModelManagerActivity, "${model.displayName} selected", Toast.LENGTH_SHORT).show()
                    finish()
                }
            },
            onDelete = { model ->
                lifecycleScope.launch {
                    File(model.filePath).delete()
                    db.modelDao().delete(model)
                    loadInstalledModels()
                }
            }
        )
        binding.rvInstalled.apply {
            adapter = installedAdapter
            layoutManager = LinearLayoutManager(this@ModelManagerActivity)
        }
    }

    private fun loadInstalledModels() {
        lifecycleScope.launch {
            val models = db.modelDao().getAll()
            if (models.isEmpty()) {
                binding.tvNoModels.visibility = View.VISIBLE
                binding.rvInstalled.visibility = View.GONE
            } else {
                binding.tvNoModels.visibility = View.GONE
                binding.rvInstalled.visibility = View.VISIBLE
                installedAdapter.submitList(models)
            }
            // Mark already installed in catalog
            catalogAdapter.setInstalledIds(models.map { it.id }.toSet())
        }
    }

    private fun startDownload(entry: ModelCatalog.CatalogEntry) {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, entry.downloadUrl)
            putExtra(DownloadService.EXTRA_FILE_NAME, entry.fileName)
            putExtra(DownloadService.EXTRA_MODEL_ID, entry.id)
        }
        startForegroundService(intent)
        catalogAdapter.setDownloadingId(entry.id)
        Toast.makeText(this, "Downloading ${entry.displayName}…", Toast.LENGTH_SHORT).show()
    }

    private fun onDownloadComplete(path: String, modelId: String) {
        val entry = ModelCatalog.getById(modelId) ?: return
        val file  = File(path)
        lifecycleScope.launch {
            db.modelDao().insert(
                ModelEntity(
                    id             = modelId,
                    displayName    = entry.displayName,
                    fileName       = entry.fileName,
                    filePath       = path,
                    sizeBytes      = file.length(),
                    promptTemplate = entry.promptTemplate
                )
            )
            catalogAdapter.clearDownloadState()
            loadInstalledModels()
            Toast.makeText(this@ModelManagerActivity, "${entry.displayName} ready!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(DownloadService.BROADCAST_PROGRESS)
            addAction(DownloadService.BROADCAST_DONE)
            addAction(DownloadService.BROADCAST_ERROR)
        }
        registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(downloadReceiver)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
