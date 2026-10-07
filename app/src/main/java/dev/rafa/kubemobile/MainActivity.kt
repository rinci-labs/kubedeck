package dev.rafa.kubemobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.KubeApp
import dev.rafa.kubemobile.ui.KubeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KubeTheme {
                KubeRoot()
            }
        }
    }
}

@Composable
private fun KubeRoot() {
    val app: AppViewModel = viewModel()
    KubeApp(app)
}
