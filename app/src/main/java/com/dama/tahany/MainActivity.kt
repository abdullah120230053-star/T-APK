package com.dama.tahany

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
        val uris = runCatching {
            when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(streamUri(intent))
                Intent.ACTION_SEND_MULTIPLE -> streamUris(intent)
                else -> return
            }
        }.getOrDefault(emptyList())
        val text = if (intent.action == Intent.ACTION_SEND && uris.isEmpty()) {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.take(16_000)?.toString()
        } else {
            null
        }
        val shareable = uris.filter { it.scheme == "content" && it.authority != "${packageName}.files" }
        viewModel.reviewIncomingShare(shareable.take(100), text)
        intent.action = null
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
}
