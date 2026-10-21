package com.taxidriver.launcher.testfixtures

import com.taxidriver.launcher.data.location.LocationQualitySetting
import com.taxidriver.launcher.data.location.LocationSettings
import com.taxidriver.launcher.data.location.LocationSettingsStore
import com.taxidriver.launcher.data.location.TrackRetentionSetting
import com.taxidriver.launcher.data.location.TripAutoResetSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [LocationSettingsStore] for view-model tests: every setter mutates a
 * [MutableStateFlow] synchronously, so a test sees the write with no DataStore IO
 * and no cross-dispatcher timing. Defaults match [LocationSettings.Default].
 */
internal class FakeLocationSettingsStore(
    initial: LocationSettings = LocationSettings.Default,
) : LocationSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<LocationSettings> = state

    override suspend fun setQuality(value: LocationQualitySetting) = state.update { it.copy(quality = value) }

    override suspend fun setIntervalMillis(value: Long) = state.update { it.copy(intervalMillis = value) }

    override suspend fun setMinUpdateDistanceMeters(value: Int) =
        state.update {
            it.copy(minUpdateDistanceMeters = value)
        }

    override suspend fun setBackgroundRangingEnabled(value: Boolean) =
        state.update {
            it.copy(backgroundRangingEnabled = value)
        }

    override suspend fun setTripAutoReset(value: TripAutoResetSetting) = state.update { it.copy(tripAutoReset = value) }

    override suspend fun setTrackRecordingEnabled(value: Boolean) =
        state.update {
            it.copy(trackRecordingEnabled = value)
        }

    override suspend fun setTrackRetention(value: TrackRetentionSetting) =
        state.update {
            it.copy(trackRetention = value)
        }

    override suspend fun resetToDefaults() = state.update { LocationSettings.Default }
}
