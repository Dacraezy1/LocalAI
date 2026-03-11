package com.localai.ui

import android.view.*
import android.widget.*
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.localai.R
import com.localai.model.HuggingFaceApi
import com.localai.model.ModelCatalog
import com.localai.model.ModelEntity

// ── Curated Catalog Adapter ───────────────────────────────────────────────────

class CatalogAdapter(
    private val onDownload: (ModelCatalog.CatalogEntry) -> Unit
) : ListAdapter<ModelCatalog.CatalogEntry, CatalogAdapter.VH>(DIFF) {

    // Track download state directly — only update the specific item
    private var downloadingId: String?   = null
    private var progress: Int            = 0
    private var speedKbps: Long          = 0
    private var etaSecs: Int             = -1
    private var installedIds: Set<String> = emptySet()

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ModelCatalog.CatalogEntry>() {
            override fun areItemsTheSame(a: ModelCatalog.CatalogEntry, b: ModelCatalog.CatalogEntry) = a.id == b.id
            override fun areContentsTheSame(a: ModelCatalog.CatalogEntry, b: ModelCatalog.CatalogEntry) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:        TextView  = view.findViewById(R.id.tv_model_name)
        val tvDesc:        TextView  = view.findViewById(R.id.tv_model_desc)
        val tvSize:        TextView  = view.findViewById(R.id.tv_model_size)
        val tvRam:         TextView  = view.findViewById(R.id.tv_model_ram)
        val btnDownload:   Button    = view.findViewById(R.id.btn_download)
        val progressBar:   ProgressBar = view.findViewById(R.id.progress_download)
        val tvProgress:    TextView  = view.findViewById(R.id.tv_progress)
        val chipRecommended: View    = view.findViewById(R.id.chip_recommended)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_catalog_model, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val isInstalled   = entry.id in installedIds
        val isDownloading = entry.id == downloadingId

        holder.tvName.text = entry.displayName
        holder.tvDesc.text = entry.description
        holder.tvSize.text = "%.1f GB".format(entry.sizeGb)
        holder.tvRam.text  = "RAM: %.1f GB".format(entry.ramRequiredGb)
        holder.chipRecommended.isVisible = entry.recommended

        when {
            isInstalled -> {
                holder.btnDownload.text = "✓ Installed"
                holder.btnDownload.isEnabled = false
                holder.btnDownload.isVisible = true
                holder.progressBar.isVisible = false
                holder.tvProgress.isVisible  = false
            }
            isDownloading -> {
                holder.btnDownload.isVisible = false
                holder.progressBar.isVisible = true
                holder.tvProgress.isVisible  = true
                if (progress >= 0) {
                    holder.progressBar.isIndeterminate = false
                    holder.progressBar.progress = progress
                    val speedStr = if (speedKbps > 1024) "%.1f MB/s".format(speedKbps / 1024f)
                                   else "${speedKbps} KB/s"
                    val etaStr   = if (etaSecs > 0) formatEta(etaSecs) else ""
                    holder.tvProgress.text = "$progress%  •  $speedStr  $etaStr"
                } else {
                    holder.progressBar.isIndeterminate = true
                    holder.tvProgress.text = "Connecting…"
                }
            }
            else -> {
                holder.btnDownload.text = "Download"
                holder.btnDownload.isEnabled = true
                holder.btnDownload.isVisible = true
                holder.progressBar.isVisible = false
                holder.tvProgress.isVisible  = false
                holder.btnDownload.setOnClickListener { onDownload(entry) }
            }
        }
    }

    /** Update only the downloading item — not notifyDataSetChanged (causes flicker) */
    fun updateDownloadState(id: String?, p: Int, speed: Long, eta: Int) {
        val prevId = downloadingId
        downloadingId = id; progress = p; speedKbps = speed; etaSecs = eta
        // Notify only affected items
        currentList.forEachIndexed { i, entry ->
            if (entry.id == prevId || entry.id == id) notifyItemChanged(i)
        }
    }

    fun setInstalledIds(ids: Set<String>) {
        val changed = ids != installedIds
        installedIds = ids
        if (changed) notifyDataSetChanged()
    }
}

// ── HuggingFace Search Results Adapter ───────────────────────────────────────

class HfModelAdapter(
    private val onBrowse: (HuggingFaceApi.HFModel) -> Unit
) : ListAdapter<HuggingFaceApi.HFModel, HfModelAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HuggingFaceApi.HFModel>() {
            override fun areItemsTheSame(a: HuggingFaceApi.HFModel, b: HuggingFaceApi.HFModel) = a.repoId == b.repoId
            override fun areContentsTheSame(a: HuggingFaceApi.HFModel, b: HuggingFaceApi.HFModel) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvRepoId:    TextView = view.findViewById(R.id.tv_repo_id)
        val tvDownloads: TextView = view.findViewById(R.id.tv_downloads)
        val tvFileCount: TextView = view.findViewById(R.id.tv_file_count)
        val tvLikes:     TextView = view.findViewById(R.id.tv_likes)
        val btnBrowse:   Button   = view.findViewById(R.id.btn_browse_files)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_hf_model, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = getItem(position)
        holder.tvRepoId.text    = m.repoId
        holder.tvDownloads.text = "↓ ${HuggingFaceApi.formatDownloads(m.downloads)}"
        holder.tvLikes.text     = "♥ ${HuggingFaceApi.formatDownloads(m.likes)}"
        val fc = m.ggufFiles.size
        holder.tvFileCount.text = if (fc > 0) "$fc GGUF file${if (fc > 1) "s" else ""}" else "No GGUF files listed"
        holder.btnBrowse.setOnClickListener { onBrowse(m) }
    }
}

// ── HF File Picker Bottom Sheet ───────────────────────────────────────────────

class HfFilePickerSheet(
    private val files: List<HuggingFaceApi.GgufFile>,
    private val downloadState: DownloadState,
    private val onDownload: (HuggingFaceApi.GgufFile) -> Unit
) : com.google.android.material.bottomsheet.BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: android.os.Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 24)
        }

        // Title
        val title = TextView(ctx).apply {
            text = "Choose a file to download"
            textSize = 16f
            setPadding(24, 20, 24, 12)
            setTextColor(resources.getColor(com.localai.R.color.on_surface, null))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = HfFileAdapter(files, downloadState) { file ->
                onDownload(file)
                dismiss()
            }
        }
        root.addView(rv)
        return root
    }
}

// ── HF File List Adapter ──────────────────────────────────────────────────────

class HfFileAdapter(
    private val files: List<HuggingFaceApi.GgufFile>,
    private val downloadState: DownloadState,
    private val onDownload: (HuggingFaceApi.GgufFile) -> Unit
) : RecyclerView.Adapter<HfFileAdapter.VH>() {

    init { downloadState.addListener(::notifyDataSetChanged) }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvFilename:   TextView   = view.findViewById(R.id.tv_filename)
        val tvSize:       TextView   = view.findViewById(R.id.tv_file_size)
        val progressBar:  ProgressBar= view.findViewById(R.id.progress_download)
        val layoutInfo:   View       = view.findViewById(R.id.layout_progress_info)
        val tvPct:        TextView   = view.findViewById(R.id.tv_progress_pct)
        val tvSpeed:      TextView   = view.findViewById(R.id.tv_speed)
        val tvEta:        TextView   = view.findViewById(R.id.tv_eta)
        val btnDownload:  Button     = view.findViewById(R.id.btn_download)
        val btnCancel:    Button     = view.findViewById(R.id.btn_cancel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_hf_file, parent, false))

    override fun getItemCount() = files.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = files[position]
        val isThis = downloadState.isDownloading && downloadState.downloadingFile == file.filename

        holder.tvFilename.text = file.filename
        holder.tvSize.text     = file.sizeLabel

        if (isThis) {
            val p = downloadState.progress
            holder.progressBar.isVisible = true
            holder.layoutInfo.isVisible  = true
            holder.btnDownload.isVisible = false
            holder.btnCancel.isVisible   = true

            if (p >= 0) {
                holder.progressBar.isIndeterminate = false
                holder.progressBar.progress = p
                holder.tvPct.text = "$p%"
                val s = downloadState.speedKbps
                holder.tvSpeed.text = if (s > 1024) "%.1f MB/s".format(s/1024f) else "${s} KB/s"
                val e = downloadState.etaSecs
                holder.tvEta.text = if (e > 0) "ETA ${formatEta(e)}" else ""
            } else {
                holder.progressBar.isIndeterminate = true
                holder.tvPct.text = "Connecting…"
                holder.tvSpeed.text = ""
                holder.tvEta.text = ""
            }
            holder.btnCancel.setOnClickListener {
                // TODO: cancel via activity
            }
        } else {
            holder.progressBar.isVisible = false
            holder.layoutInfo.isVisible  = false
            holder.btnDownload.isVisible = !downloadState.isDownloading
            holder.btnCancel.isVisible   = false
            holder.btnDownload.setOnClickListener { onDownload(file) }
        }
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        downloadState.removeListener(::notifyDataSetChanged)
    }
}

// ── Installed Model Adapter ───────────────────────────────────────────────────

class InstalledModelAdapter(
    private val onActivate: (ModelEntity) -> Unit,
    private val onDelete: (ModelEntity) -> Unit
) : ListAdapter<ModelEntity, InstalledModelAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ModelEntity>() {
            override fun areItemsTheSame(a: ModelEntity, b: ModelEntity) = a.id == b.id
            override fun areContentsTheSame(a: ModelEntity, b: ModelEntity) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:     TextView   = view.findViewById(R.id.tv_model_name)
        val tvSize:     TextView   = view.findViewById(R.id.tv_model_size)
        val btnActivate: Button    = view.findViewById(R.id.btn_activate)
        val btnDelete:  ImageButton = view.findViewById(R.id.btn_delete)
        val ivActive:   View       = view.findViewById(R.id.iv_active)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_installed_model, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = getItem(position)
        holder.tvName.text = m.displayName
        holder.tvSize.text = "%.1f GB".format(m.sizeBytes / 1e9f)
        holder.ivActive.isVisible = m.isActive

        if (m.isActive) {
            holder.btnActivate.text = "Active"
            holder.btnActivate.isEnabled = false
        } else {
            holder.btnActivate.text = "Use"
            holder.btnActivate.isEnabled = true
            holder.btnActivate.setOnClickListener { onActivate(m) }
        }
        holder.btnDelete.setOnClickListener { onDelete(m) }
    }
}

// ── Shared helper ─────────────────────────────────────────────────────────────

fun formatEta(secs: Int): String = when {
    secs < 60   -> "${secs}s"
    secs < 3600 -> "${secs / 60}m ${secs % 60}s"
    else        -> "${secs / 3600}h ${(secs % 3600) / 60}m"
}
