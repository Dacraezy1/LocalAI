package com.localai.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.localai.R
import io.noties.markwon.Markwon

class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val VIEW_USER      = 0
        private const val VIEW_ASSISTANT = 1
        private const val VIEW_SYSTEM    = 2

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }

    private var markwon: Markwon? = null

    // ── ViewHolders ──────────────────────────────────────────────────────────

    inner class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tv_message)
    }

    inner class AssistantVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView     = view.findViewById(R.id.tv_message)
        val loader: View         = view.findViewById(R.id.loading_dots)
    }

    inner class SystemVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tv_message)
    }

    // ── Adapter overrides ────────────────────────────────────────────────────

    override fun getItemViewType(position: Int) = when (getItem(position).role) {
        ChatRole.USER      -> VIEW_USER
        ChatRole.ASSISTANT -> VIEW_ASSISTANT
        ChatRole.SYSTEM    -> VIEW_SYSTEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (markwon == null) markwon = Markwon.create(parent.context)

        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_USER      -> UserVH(inflater.inflate(R.layout.item_message_user, parent, false))
            VIEW_ASSISTANT -> AssistantVH(inflater.inflate(R.layout.item_message_assistant, parent, false))
            else           -> SystemVH(inflater.inflate(R.layout.item_message_system, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is UserVH -> holder.tvText.text = msg.content

            is AssistantVH -> {
                if (msg.isLoading && msg.content.isEmpty()) {
                    holder.tvText.visibility = View.GONE
                    holder.loader.visibility = View.VISIBLE
                } else {
                    holder.tvText.visibility = View.VISIBLE
                    holder.loader.visibility = View.GONE
                    // Render markdown for assistant responses
                    if (msg.content.contains("```") || msg.content.contains("**") || msg.content.contains("- ")) {
                        markwon?.setMarkdown(holder.tvText, msg.content)
                    } else {
                        holder.tvText.text = msg.content
                    }
                    // Blinking cursor while streaming
                    if (msg.isLoading) {
                        holder.tvText.text = "${holder.tvText.text}▌"
                    }
                }
            }

            is SystemVH -> holder.tvText.text = msg.content
        }
    }
}
