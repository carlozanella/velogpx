package ch.cld9.velogpx

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ch.cld9.velogpx.ui.EditorScreen
import ch.cld9.velogpx.ui.EditorViewModel
import ch.cld9.velogpx.ui.VeloGpxTheme

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VeloGpxTheme { EditorScreen(viewModel) } }
        if (savedInstanceState == null) importFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importFromIntent(intent)
    }

    private fun importFromIntent(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableUri(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableUriList(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) viewModel.importUris(uris)
    }
}

private fun Intent.parcelableUri(key: String): Uri? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(key, Uri::class.java)
} else {
    @Suppress("DEPRECATION") getParcelableExtra(key)
}

private fun Intent.parcelableUriList(key: String): List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
} else {
    @Suppress("DEPRECATION") getParcelableArrayListExtra<Uri>(key).orEmpty()
}

