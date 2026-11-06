package com.vlad.tester.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared control state so the status bar can trigger QA evidence receiver actions.
 */
internal class EvidenceStatusController {
    var receivingEnabled by mutableStateOf(false)
    var canEnable by mutableStateOf(false)
    var canPair by mutableStateOf(false)
    var canStop by mutableStateOf(false)

    var onEnable: (() -> Unit)? = null
    var onPair: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
}

