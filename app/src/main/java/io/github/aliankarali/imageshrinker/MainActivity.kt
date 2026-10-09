package io.github.aliankarali.imageshrinker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.aliankarali.imageshrinker.engine.InputSource
import io.github.aliankarali.imageshrinker.ui.MainViewModel
import io.github.aliankarali.imageshrinker.ui.ShrinkerApp
import io.github.aliankarali.imageshrinker.ui.theme.ShrinkerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after a rotation the intent was already handled.
        if (savedInstanceState == null) handle(intent)
        setContent {
            ShrinkerTheme {
                ShrinkerApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Images shared to the app, or opened with it, are shrunk straight away. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        // Senders put the URIs they grant access to in ClipData; take those too.
        val clip = intent.clipData
        val clipped = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        val all = (uris + clipped).distinct()
        if (all.isNotEmpty()) viewModel.handle(listOf(InputSource.Shared(all)))
    }
}
