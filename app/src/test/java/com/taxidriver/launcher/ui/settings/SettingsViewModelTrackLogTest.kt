package com.taxidriver.launcher.ui.settings

import android.net.Uri
import com.taxidriver.launcher.data.calendar.CalendarCatalogState
import com.taxidriver.launcher.data.location.TrackRetentionSetting
import com.taxidriver.launcher.data.location.VehicleMotion
import com.taxidriver.launcher.testfixtures.FakeCalendarPreferencesStore
import com.taxidriver.launcher.testfixtures.FakeDisplaySettingsStore
import com.taxidriver.launcher.testfixtures.FakeDockSettingsStore
import com.taxidriver.launcher.testfixtures.FakeFontSelectionStore
import com.taxidriver.launcher.testfixtures.FakeLocationSettingsStore
import com.taxidriver.launcher.testfixtures.FakeTrackLogPort
import com.taxidriver.launcher.testfixtures.FakeUpdateSettingsStore
import com.taxidriver.launcher.testfixtures.FakeUpdaterPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

// Robolectric (unlike the sibling pure-JVM SettingsViewModelTest) because
// SettingsAction.ExportTrackLog carries a real android.net.Uri.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsViewModelTrackLogTest {
    private val locationStore = FakeLocationSettingsStore()
    private val trackLog = FakeTrackLogPort()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        SettingsViewModel(
            FakeDisplaySettingsStore(),
            FakeFontSelectionStore(),
            locationStore,
            FakeCalendarPreferencesStore(),
            FakeDockSettingsStore(),
            trackLog = trackLog,
            availableCalendars = flowOf(CalendarCatalogState(hasAccess = true, calendars = emptyList())),
            updater = FakeUpdaterPort(),
            updatePreferences = FakeUpdateSettingsStore(),
            motion = flowOf(VehicleMotion.PARKED),
        )

    @Test
    fun `persists the track recording toggle and retention`() =
        runTest {
            val vm = viewModel()
            vm.onAction(SettingsAction.SetTrackRecording(false))
            vm.onAction(SettingsAction.SetTrackRetention(TrackRetentionSetting.DAYS_30))
            advanceUntilIdle()

            val settings = locationStore.settings.first()
            assertEquals(false, settings.trackRecordingEnabled)
            assertEquals(TrackRetentionSetting.DAYS_30, settings.trackRetention)
        }

    @Test
    fun `export success reports the exported point count`() =
        runTest {
            trackLog.exportResult = 1_234L
            val vm = viewModel()
            vm.onAction(SettingsAction.ExportTrackLog(Uri.parse("content://test/track.gpx")))
            advanceUntilIdle()

            // first { } rather than first(): the stateIn seed (Idle) is what a
            // plain first() returns before the upstream combine ever runs.
            val exported = vm.uiState.first { it.trackExport != TrackExportState.Idle }
            assertEquals(TrackExportState.Done(1_234L), exported.trackExport)
            assertEquals(1, trackLog.exportedTo.size)
        }

    @Test
    fun `export failure reports Failed`() =
        runTest {
            trackLog.exportResult = null
            val vm = viewModel()
            vm.onAction(SettingsAction.ExportTrackLog(Uri.parse("content://test/track.gpx")))
            advanceUntilIdle()

            val exported = vm.uiState.first { it.trackExport != TrackExportState.Idle }
            assertEquals(TrackExportState.Failed, exported.trackExport)
        }

    @Test
    fun `clear history delegates to the port`() =
        runTest {
            val vm = viewModel()
            vm.onAction(SettingsAction.ClearTrackHistory)
            advanceUntilIdle()

            assertEquals(1, trackLog.clearCalls)
        }
}
