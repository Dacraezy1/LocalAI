package com.localai.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.localai.R
import com.localai.databinding.ActivityChatBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupRecyclerView()
        setupInput()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.rvChat.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            itemAnimator = null  // disable for performance
        }
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnStop.setOnClickListener { viewModel.stopGeneration() }

        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        binding.btnModel.setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }
    }

    private fun sendMessage() {
        val text = binding.etInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        binding.etInput.text?.clear()
        viewModel.sendMessage(text)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.messages.collectLatest { messages ->
                chatAdapter.submitList(messages.toList()) {
                    if (messages.isNotEmpty()) {
                        binding.rvChat.smoothScrollToPosition(messages.size - 1)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is ChatViewModel.UiState.Idle -> {
                        setInputEnabled(false)
                        binding.tvStatus.text = "No model loaded"
                        binding.tvStatus.visibility = View.VISIBLE
                    }
                    is ChatViewModel.UiState.LoadingModel -> {
                        setInputEnabled(false)
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvStatus.text = "Loading model…"
                        binding.tvStatus.visibility = View.VISIBLE
                    }
                    is ChatViewModel.UiState.Ready -> {
                        setInputEnabled(true)
                        binding.progressBar.visibility = View.GONE
                        binding.tvStatus.visibility = View.GONE
                        binding.btnSend.visibility = View.VISIBLE
                        binding.btnStop.visibility = View.GONE
                    }
                    is ChatViewModel.UiState.Generating -> {
                        setInputEnabled(true)
                        binding.btnSend.visibility = View.GONE
                        binding.btnStop.visibility = View.VISIBLE
                    }
                    is ChatViewModel.UiState.Error -> {
                        setInputEnabled(false)
                        binding.progressBar.visibility = View.GONE
                        binding.tvStatus.text = "Error: ${state.msg}"
                        binding.tvStatus.visibility = View.VISIBLE
                        Toast.makeText(this@ChatActivity, state.msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.tokenSpeed.collectLatest { speed ->
                if (speed > 0) {
                    binding.tvSpeed.text = String.format("%.1f tok/s", speed)
                    binding.tvSpeed.visibility = View.VISIBLE
                } else {
                    binding.tvSpeed.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.activeModel.collectLatest { model ->
                if (model != null) {
                    supportActionBar?.subtitle = model.displayName
                    viewModel.loadModelIfNeeded(model)
                } else {
                    supportActionBar?.subtitle = "No model selected"
                }
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        binding.etInput.isEnabled = enabled
        binding.btnSend.isEnabled = enabled
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear -> { viewModel.clearChat(); true }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
