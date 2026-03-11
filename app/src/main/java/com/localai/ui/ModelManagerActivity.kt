package com.localai.ui

import android.content.*
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayoutMediator
import com.localai.databinding.ActivityModelManagerBinding
import com.localai.databinding.FragmentCatalogBinding
import com.localai.databinding.FragmentHfSearchBinding
import com.localai.databinding.FragmentInstalledBinding
import com.localai.model.*
import kotlinx.coroutines.launch
import java.io.File

// ─── Main Activity ────────────────────────────────────────────────────────────

class ModelManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelManagerBinding
    private val dlState = DownloadState()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                DownloadService.BROADCAST_PROGRESS -> dlState.update(
                    intent.getIntExtra(DownloadService.EXTRA_PROGRESS, -1),
                    intent.getLongExtra(DownloadService.EXTRA_SPEED_KBPS, 0),
                    intent.getIntExtra(DownloadService.EXTRA_ETA_SECS, -1),
                    intent.getLongExtra(DownloadService.EXTRA_DOWNLOADED_BYTES, 0),
                    intent.getLongExtra(DownloadService.EXTRA_TOTAL_BYTES, 0)
                )
                DownloadService.BROADCAST_DONE -> {
                    val path    = intent.getStringExtra(DownloadService.EXTRA_DOWNLOADED_PATH) ?: return
                    val modelId = intent.getStringExtra(DownloadService.EXTRA_MODEL_ID) ?: return
                    dlState.markComplete()
                    onDownloadComplete(path, modelId)
                }
                DownloadService.BROADCAST_ERROR -> {
                    val msg = intent.getStringExtra(DownloadService.EXTRA_ERROR_MSG) ?: "Unknown"
                    dlState.markError()
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

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(pos: Int): Fragment = when (pos) {
                0 -> CatalogFragment(dlState) { e -> startDownload(e.downloadUrl, e.fileName, e.id, e.displayName) }
                1 -> HuggingFaceFragment(dlState) { url, file, id -> startDownload(url, file, id, file) }
                else -> InstalledFragment()
            }
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = when (pos) { 0 -> "Catalog"; 1 -> "HuggingFace"; else -> "Installed" }
        }.attach()
    }

    fun startDownload(url: String, fileName: String, modelId: String, displayName: String) {
        if (dlState.isDownloading) {
            Toast.makeText(this, "A download is already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        dlState.markStart(modelId, fileName)
        startForegroundService(Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_FILE_NAME, fileName)
            putExtra(DownloadService.EXTRA_MODEL_ID, modelId)
            putExtra(DownloadService.EXTRA_DISPLAY_NAME, displayName)
        })
        Toast.makeText(this, "Downloading $displayName...", Toast.LENGTH_SHORT).show()
    }

    private fun onDownloadComplete(path: String, modelId: String) {
        val file  = File(path)
        val entry = ModelCatalog.getById(modelId)
        lifecycleScope.launch {
            AppDatabase.getInstance(this@ModelManagerActivity).modelDao().insert(
                ModelEntity(
                    id             = modelId,
                    displayName    = entry?.displayName ?: file.nameWithoutExtension,
                    fileName       = file.name,
                    filePath       = path,
                    sizeBytes      = file.length(),
                    promptTemplate = entry?.promptTemplate ?: "chatml"
                )
            )
            Toast.makeText(this@ModelManagerActivity,
                "${entry?.displayName ?: file.nameWithoutExtension} ready!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter().apply {
            addAction(DownloadService.BROADCAST_PROGRESS)
            addAction(DownloadService.BROADCAST_DONE)
            addAction(DownloadService.BROADCAST_ERROR)
        }, RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() { super.onPause(); unregisterReceiver(receiver) }
    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
}

// ─── Download state (shared across fragments via activity) ────────────────────

class DownloadState {
    var isDownloading   = false; private set
    var downloadingId   = "";    private set
    var downloadingFile = "";    private set
    var progress        = 0;     private set
    var speedKbps       = 0L;    private set
    var etaSecs         = -1;    private set
    var downloadedBytes = 0L;    private set
    var totalBytes      = 0L;    private set

    private val callbacks = mutableListOf<() -> Unit>()

    fun addListener(cb: () -> Unit)    { callbacks.add(cb) }
    fun removeListener(cb: () -> Unit) { callbacks.remove(cb) }

    @JvmName("dispatchCallbacks")
    private fun dispatch() { callbacks.forEach { it() } }

    fun markStart(id: String, file: String) {
        isDownloading = true; downloadingId = id; downloadingFile = file
        progress = 0; speedKbps = 0; etaSecs = -1
        dispatch()
    }

    fun update(p: Int, s: Long, e: Int, dl: Long, total: Long) {
        progress = p; speedKbps = s; etaSecs = e; downloadedBytes = dl; totalBytes = total
        dispatch()
    }

    fun markComplete() { isDownloading = false; progress = 100; dispatch() }
    fun markError()    { isDownloading = false; dispatch() }
}

// ─── Fragment: Curated Catalog ─────────────────────────────────────────────────

class CatalogFragment(
    private val dlState: DownloadState,
    private val onDownload: (ModelCatalog.CatalogEntry) -> Unit
) : Fragment(com.localai.R.layout.fragment_catalog) {

    private var _b: FragmentCatalogBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: CatalogAdapter

    private val stateListener: () -> Unit = {
        adapter.updateDownloadState(
            dlState.downloadingId.takeIf { dlState.isDownloading },
            dlState.progress, dlState.speedKbps, dlState.etaSecs
        )
        if (!dlState.isDownloading) refreshInstalled()
    }

    override fun onViewCreated(view: View, savedState: Bundle?) {
        super.onViewCreated(view, savedState)
        _b = FragmentCatalogBinding.bind(view)
        adapter = CatalogAdapter(onDownload)
        b.rvCatalog.layoutManager = LinearLayoutManager(requireContext())
        b.rvCatalog.adapter = adapter
        adapter.submitList(ModelCatalog.models)
        refreshInstalled()
        dlState.addListener(stateListener)
    }

    override fun onResume() { super.onResume(); refreshInstalled() }

    private fun refreshInstalled() {
        lifecycleScope.launch {
            val ids = AppDatabase.getInstance(requireContext()).modelDao().getAll().map { it.id }.toSet()
            adapter.setInstalledIds(ids)
        }
    }

    override fun onDestroyView() {
        dlState.removeListener(stateListener)
        _b = null
        super.onDestroyView()
    }
}

// ─── Fragment: HuggingFace Search ─────────────────────────────────────────────

class HuggingFaceFragment(
    private val dlState: DownloadState,
    private val onDownloadFile: (url: String, fileName: String, modelId: String) -> Unit
) : Fragment(com.localai.R.layout.fragment_hf_search) {

    private var _b: FragmentHfSearchBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: HfModelAdapter

    private val suggestions = listOf("phi", "qwen", "llama", "mistral", "gemma", "tinyllama", "smollm", "stablelm")

    override fun onViewCreated(view: View, savedState: Bundle?) {
        super.onViewCreated(view, savedState)
        _b = FragmentHfSearchBinding.bind(view)

        adapter = HfModelAdapter { model -> showFilePicker(model) }
        b.rvHfResults.layoutManager = LinearLayoutManager(requireContext())
        b.rvHfResults.adapter = adapter

        suggestions.forEach { s ->
            val chip = Chip(requireContext()).apply {
                text = s
                isCheckable = false
                setOnClickListener { b.etSearch.setText(s); doSearch(s) }
            }
            b.chipGroupSuggestions.addView(chip)
        }

        b.btnSearch.setOnClickListener { doSearch(b.etSearch.text.toString().trim()) }
        b.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(b.etSearch.text.toString().trim()); true }
            else false
        }
    }

    private fun doSearch(query: String) {
        if (query.isBlank()) return
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(b.etSearch.windowToken, 0)

        b.progressHf.isVisible  = true
        b.tvHfStatus.isVisible   = false
        b.rvHfResults.isVisible  = false

        lifecycleScope.launch {
            val result = HuggingFaceApi.searchModels(query)
            b.progressHf.isVisible = false
            when {
                result.error != null -> {
                    b.tvHfStatus.text    = "Error: ${result.error}"
                    b.tvHfStatus.isVisible = true
                }
                result.models.isEmpty() -> {
                    b.tvHfStatus.text    = "No GGUF models found for \"$query\""
                    b.tvHfStatus.isVisible = true
                }
                else -> {
                    adapter.submitList(result.models)
                    b.rvHfResults.isVisible = true
                }
            }
        }
    }

    private fun showFilePicker(model: HuggingFaceApi.HFModel) {
        lifecycleScope.launch {
            val result = HuggingFaceApi.getGgufFiles(model.repoId)
            if (result.error != null || result.files.isEmpty()) {
                Toast.makeText(requireContext(), "No GGUF files found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            HfFilePickerSheet(result.files, dlState) { file ->
                onDownloadFile(file.downloadUrl, file.filename, "${model.repoId}/${file.filename}")
            }.show(parentFragmentManager, "file_picker")
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}

// ─── Fragment: Installed Models ────────────────────────────────────────────────

class InstalledFragment : Fragment(com.localai.R.layout.fragment_installed) {

    private var _b: FragmentInstalledBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: InstalledModelAdapter

    override fun onViewCreated(view: View, savedState: Bundle?) {
        super.onViewCreated(view, savedState)
        _b = FragmentInstalledBinding.bind(view)
        adapter = InstalledModelAdapter(
            onActivate = { model ->
                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(requireContext())
                    db.modelDao().clearActive()
                    db.modelDao().setActive(model.id)
                    Toast.makeText(requireContext(), "${model.displayName} selected", Toast.LENGTH_SHORT).show()
                    requireActivity().finish()
                }
            },
            onDelete = { model ->
                lifecycleScope.launch {
                    File(model.filePath).delete()
                    AppDatabase.getInstance(requireContext()).modelDao().delete(model)
                    loadModels()
                }
            }
        )
        b.rvInstalled.layoutManager = LinearLayoutManager(requireContext())
        b.rvInstalled.adapter = adapter
    }

    override fun onResume() { super.onResume(); loadModels() }

    private fun loadModels() {
        lifecycleScope.launch {
            val models = AppDatabase.getInstance(requireContext()).modelDao().getAll()
            b.tvNoModels.isVisible  = models.isEmpty()
            b.rvInstalled.isVisible = models.isNotEmpty()
            adapter.submitList(models)
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
