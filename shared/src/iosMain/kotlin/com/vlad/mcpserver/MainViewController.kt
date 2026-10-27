package com.vlad.mcpserver

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController {
    // The AndroidX lifecycle viewModel() factory is not wired on iOS; create
    // the shared view model explicitly and keep it for the composition.
    val vm = remember { AppViewModel() }
    App(viewModel = vm)
}
