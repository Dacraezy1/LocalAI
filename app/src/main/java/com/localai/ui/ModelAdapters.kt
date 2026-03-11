package com.localai.ui

import android.view.*
import android.widget.*
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.localai.R
import com.localai.model.ModelCatalog
import com.localai.model.ModelEntity

// ── Catalog Adapter (download list) ─────────────────────────────────────────

class CatalogAdapter(
    private val onDownload: (ModelCatalog.CatalogEntry) -> Unit
) : ListAdapter<ModelCatalog.CatalogEntry, CatalogAdapter.VH>(DIFF) {

    private var downloadingId: String? = null
    private var downloadProgress: Int = 0
    private var downloadSpeedKbps: Long = 0
    private var installedIds: Set<String> = emptySet()

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ModelCatalog.CatalogEntry>() {
            override fun areItemsTheSame(a: ModelCatalog.CatalogEntry, b: ModelCatalog.CatalogEntry) = a.id == b.id
            override fun areContentsTheSame(a: ModelCatalog.CatalogEntry, b: ModelCatalog.CatalogEntry) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView        = view.findViewById(R.id.tv_model_name)
        val tvDesc: TextView        = view.findViewById(R.id.tv_model_desc)
        val tvSize: TextView        = view.findViewById(R.id.tv_model_size)
        val tvRam: TextView         = view.findViewById(R.id.tv_model_ram)
        val btnDownload: Button     = view.findViewById(R.id.btn_download)
        val progressBar: ProgressBar= view.findViewById(R.id.progress_download)
        val tvProgress: TextView    = view.findViewById(R.id.tv_progress)
        val chipRecommended: View   = view.findViewById(R.id.chip_recommended)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_catalog_model, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val isInstalled   = entry.id in installedIds
        val isDownloading = entry.id == downloadingId

        holder.tvName.text = entry.displayName
        holder.tvDesc.text = entry.description
        holder.tvSize.text = String.format("%.1f GB", entry.sizeGb)
        holder.tvRam.text  = String.format("RAM: %.1f GB", entry.ramRequiredGb)
        holder.chipRecommended.isVisible = entry.recommended

        when {
            isInstalled -> {
                holder.btnDownload.text = "Installed"
                holder.btnDownload.isEnabled = false
                holder.progressBar.isVisible = false
                holder.tvProgress.isVisible  = false
            }
            isDownloading -> {
                holder.btnDownload.isVisible = false
                holder.progressBar.isVisible = true
                holder.tvProgress.isVisible  = true
                if (downloadProgress >= 0) {
                    holder.progressBar.isIndeterminate = false
                    holder.progressBar.progress = downloadProgress
                    holder.tvProgress.text = "$downloadProgress%  •  ${downloadSpeedKbps} KB/s"
                } else {
                    holder.progressBar.isIndeterminate = true
                    holder.tvProgress.text = "Connecting…"
                }
            }
            else -> {
                holder.btnDownload.text      = "Download"
                holder.btnDownload.isEnabled = true
                holder.btnDownload.isVisible = true
                holder.progressBar.isVisible = false
                holder.tvProgress.isVisible  = false
                holder.btnDownload.setOnClickListener { onDownload(entry) }
            }
        }
    }

    fun setDownloadingId(id: String) {
        downloadingId = id
        notifyDataSetChanged()
    }

    fun updateDownloadProgress(progress: Int, speedKbps: Long, etaSecs: Int) {
        downloadProgress  = progress
        downloadSpeedKbps = speedKbps
        notifyDataSetChanged()
    }

    fun clearDownloadState() {
        downloadingId = null
        downloadProgress = 0
        notifyDataSetChanged()
    }

    fun setInstalledIds(ids: Set<String>) {
        installedIds = ids
        notifyDataSetChanged()
    }
}

// ── Installed Model Adapter ──────────────────────────────────────────────────

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
        val tvName: TextView    = view.findViewById(R.id.tv_model_name)
        val tvSize: TextView    = view.findViewById(R.id.tv_model_size)
        val btnActivate: Button = view.findViewById(R.id.btn_activate)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
        val ivActive: View      = view.findViewById(R.id.iv_active)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_installed_model, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val model = getItem(position)
        holder.tvName.text = model.displayName
        holder.tvSize.text = String.format("%.1f GB", model.sizeBytes / 1e9f)
        holder.ivActive.isVisible = model.isActive

        if (model.isActive) {
            holder.btnActivate.text = "Active"
            holder.btnActivate.isEnabled = false
        } else {
            holder.btnActivate.text = "Use"
            holder.btnActivate.isEnabled = true
            holder.btnActivate.setOnClickListener { onActivate(model) }
        }
        holder.btnDelete.setOnClickListener { onDelete(model) }
    }
}
