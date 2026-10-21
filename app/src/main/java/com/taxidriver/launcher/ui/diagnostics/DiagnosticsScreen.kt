package com.taxidriver.launcher.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.taxidriver.launcher.R
import com.taxidriver.launcher.data.diagnostics.DiagnosticFact
import com.taxidriver.launcher.data.diagnostics.DiagnosticSection
import com.taxidriver.launcher.data.diagnostics.FactHealth
import com.taxidriver.launcher.data.diagnostics.FactValue
import com.taxidriver.launcher.data.diagnostics.SectionId
import com.taxidriver.launcher.data.diagnostics.SectionPayload
import com.taxidriver.launcher.data.diagnostics.issueCount
import com.taxidriver.launcher.data.diagnostics.issues
import com.taxidriver.launcher.ui.diagnostics.components.DiagnosticsSectionCard
import com.taxidriver.launcher.ui.diagnostics.components.DiagnosticsSummary
import com.taxidriver.launcher.ui.theme.FemtoDimens
import com.taxidriver.launcher.ui.theme.FemtoIcon
import com.taxidriver.launcher.ui.theme.FemtoTheme
import com.taxidriver.launcher.ui.theme.PreviewLightDark

/**
 * Per-feature health readout: which gate (grant, listener, capture engine,
 * network) is blocking which dashboard feature, plus the app's own recent
 * warning logs. The launcher degrades silently by design, and deployed head
 * units are rarely adb-reachable — this screen plus the copyable report is
 * the remote-debugging surface.
 */
@Composable
internal fun DiagnosticsScreen(
    uiState: DiagnosticsUiState,
    onAction: (DiagnosticsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Manual toggles layered over the health default: failure sections start
    // expanded, healthy ones collapsed, and a tap flips either — XOR lets one
    // saveable set carry both overrides across recreation.
    var toggled by rememberSaveable(stateSaver = ToggledSectionsSaver) { mutableStateOf(emptySet<String>()) }
    val visibleSections =
        if (uiState.problemsOnly) {
            uiState.sections.filter { it.issues().isNotEmpty() }
        } else {
            uiState.sections
        }
    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Header(onBack = onBack) }
        item { DiagnosticsSummary(uiState = uiState, onAction = onAction) }
        items(visibleSections, key = { it.id.name }) { section ->
            DiagnosticsSectionCard(
                section = section,
                expanded = section.issues().isNotEmpty() xor (section.id.name in toggled),
                problemsOnly = uiState.problemsOnly,
                onToggle = {
                    toggled =
                        if (section.id.name in toggled) toggled - section.id.name else toggled + section.id.name
                },
            )
        }
        if (uiState.problemsOnly && uiState.sections.issueCount() == 0) {
            item {
                // Claim health only once every collector has reported; while
                // sections are still streaming in, an all-clear would be a
                // verdict on data that does not exist yet.
                val collected = uiState.sections.all { it.payload != null }
                val emptyStateRes =
                    if (collected) R.string.diagnostics_no_problems else R.string.diagnostics_section_collecting
                Text(
                    text = stringResource(emptyStateRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// rememberSaveable cannot persist a Set directly; round-trip it through the
// list form the saved-instance-state bundle understands.
private val ToggledSectionsSaver =
    listSaver<Set<String>, String>(
        save = { it.toList() },
        restore = { it.toSet() },
    )

@Composable
private fun Header(onBack: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(FemtoDimens.MinTouchTarget)) {
            FemtoIcon(
                imageVector = Lucide.ArrowLeft,
                contentDescription = stringResource(R.string.settings_back),
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = stringResource(R.string.diagnostics_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }

@PreviewLightDark
@Composable
private fun DiagnosticsScreenPreview() {
    FemtoTheme {
        DiagnosticsScreen(
            uiState =
                DiagnosticsUiState(
                    sections =
                        listOf(
                            DiagnosticSection(
                                SectionId.APP,
                                SectionPayload.Facts(listOf(DiagnosticFact("App", FactValue.Text("1.0 (debug)")))),
                            ),
                            DiagnosticSection(
                                SectionId.NETWORK,
                                SectionPayload.Facts(
                                    listOf(DiagnosticFact("Online", FactValue.Status("offline", FactHealth.ERROR))),
                                ),
                            ),
                            DiagnosticSection(SectionId.LOGS, null),
                        ),
                ),
            onAction = {},
            onBack = {},
        )
    }
}
