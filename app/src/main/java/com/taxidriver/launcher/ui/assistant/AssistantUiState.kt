package com.taxidriver.launcher.ui.assistant

import com.taxidriver.launcher.data.voice.VoiceState

/** State for the assistant sheet: the in-launcher voice recognition step. */
internal data class AssistantUiState(
    val voice: VoiceState,
)

/** Intents the assistant sheet reports up to its ViewModel. */
internal sealed interface AssistantAction {
    data object StartListening : AssistantAction

    data object StopListening : AssistantAction

    /** Return to the ready state, discarding the current result or error. */
    data object Reset : AssistantAction
}
