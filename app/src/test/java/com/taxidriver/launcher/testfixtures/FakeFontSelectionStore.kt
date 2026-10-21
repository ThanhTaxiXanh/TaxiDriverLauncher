package com.taxidriver.launcher.testfixtures

import com.taxidriver.launcher.data.fonts.FontSelection
import com.taxidriver.launcher.data.fonts.FontSelectionStore
import com.taxidriver.launcher.data.fonts.FontSlot
import com.taxidriver.launcher.data.fonts.FontSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [FontSelectionStore] for repository tests. Backed by a
 * [MutableStateFlow], which — like DataStore — skips the re-emission when an
 * unchanged selection is written, so the repository's retry trigger for
 * re-chosen families is exercised faithfully.
 */
internal class FakeFontSelectionStore(
    initial: FontSelection = FontSelection.System,
) : FontSelectionStore {
    private val state = MutableStateFlow(initial)

    override val selection: Flow<FontSelection> = state

    override suspend fun setSource(
        slot: FontSlot,
        source: FontSource,
    ) = state.update { it.with(slot, source) }

    override suspend fun resetToDefaults() {
        state.value = FontSelection.System
    }
}
