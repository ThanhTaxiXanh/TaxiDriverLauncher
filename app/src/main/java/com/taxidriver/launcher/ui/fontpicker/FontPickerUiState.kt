package com.taxidriver.launcher.ui.fontpicker

import com.taxidriver.launcher.data.fonts.FontSlot
import com.taxidriver.launcher.data.fonts.FontSource
import com.taxidriver.launcher.data.fonts.GoogleFontFamily
import com.taxidriver.launcher.data.fonts.SystemFontFamily

/** Catalog load state for the picker list. */
internal enum class PickerStatus {
    LOADING,
    READY,
    ERROR,
}

/**
 * State for the font picker. [families] (Google Fonts) and [systemFonts]
 * (installed on the device) are both already filtered to the [slot]
 * (CJK-capable only, for the fallback slot) and the current [query]; the
 * separate "system default" entry is rendered by the screen, not listed here.
 */
internal data class FontPickerUiState(
    val slot: FontSlot,
    val query: String = "",
    val selectedSource: FontSource = FontSource.SystemDefault,
    val families: List<GoogleFontFamily> = emptyList(),
    val systemFonts: List<SystemFontFamily> = emptyList(),
    val downloading: Set<String> = emptySet(),
    val downloadFailed: Set<String> = emptySet(),
    val status: PickerStatus = PickerStatus.LOADING,
)

/** User intents from the picker. */
internal sealed interface FontPickerAction {
    data class Search(
        val query: String,
    ) : FontPickerAction

    /** Choose a font source for the slot; [FontSource.SystemDefault] restores the system font. */
    data class Choose(
        val source: FontSource,
    ) : FontPickerAction
}
