package com.dama.tahany

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.dama.tahany.service.TahanyService
import com.dama.tahany.ui.TahanyScreen
import com.dama.tahany.ui.TahanyViewModel
import com.dama.tahany.ui.theme.TahanyTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TahanyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TahanyTheme {
                TahanyScreen(viewModel)
            }
        }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uris = mutableListOf<Uri>()
                streamUri(intent)?.let(uris::add)
                if (uris.isNotEmpty()) {
                    viewModel.sendUris(uris)
                } else {
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    if (!text.isNullOrBlank()) sendText(text)
                }
                intent.action = null
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = streamUris(intent)
                if (uris.isNotEmpty()) viewModel.sendUris(uris)
                intent.action = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    private fun sendText(text: String) {
        val intent = Intent(this, TahanyService::class.java)
            .setAction(TahanyService.ACTION_SEND_TEXT)
            .putExtra(TahanyService.EXTRA_TEXT, text)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (_: Exception) {
            // The link state will explain that the service is unavailable.
        }
    }
}
