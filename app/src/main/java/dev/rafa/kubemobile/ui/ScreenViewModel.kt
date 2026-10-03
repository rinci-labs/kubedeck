package dev.rafa.kubemobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Builds a screen view model from the app-wide [AppViewModel], wiring in a [SavedStateHandle] so
 * editor text and selections survive process death as well as rotation.
 */
@Composable
inline fun <reified VM : ViewModel> screenViewModel(
    app: AppViewModel,
    crossinline create: (AppViewModel, SavedStateHandle) -> VM,
): VM {
    val factory = remember(app) {
        viewModelFactory {
            initializer { create(app, createSavedStateHandle()) }
        }
    }
    return viewModel(factory = factory)
}
