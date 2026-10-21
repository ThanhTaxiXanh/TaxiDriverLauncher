package com.taxidriver.launcher.ui.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taxidriver.launcher.ui.common.ImmersiveSheetEffect
import com.taxidriver.launcher.ui.common.rememberSheetHeight
import com.taxidriver.launcher.ui.theme.FemtoDimens

/**
 * The Diagnostics screen as a Material 3 [ModalBottomSheet] over settings,
 * mirroring the font-picker hosting. Starts expanded — the point is reading
 * a report, not peeking at one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsSheet(
    onDismiss: () -> Unit,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val sheetHeight = rememberSheetHeight(FemtoDimens.DrawerSheetHeightFraction)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ImmersiveSheetEffect(fullscreen)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(sheetHeight),
        ) {
            DiagnosticsRoute(onBack = onDismiss)
        }
    }
}
