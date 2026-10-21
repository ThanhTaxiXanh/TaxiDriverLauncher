package com.taxidriver.launcher.ui.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.taxidriver.launcher.R
import com.taxidriver.launcher.data.display.ClockSetting
import com.taxidriver.launcher.data.display.SpeedUnitSetting
import com.taxidriver.launcher.data.display.TemperatureUnitSetting
import com.taxidriver.launcher.ui.settings.SettingsAction
import com.taxidriver.launcher.ui.settings.SettingsUiState

// The Units category's rows; see AppearanceSection's header comment on why
// there is no title / reset wiring here.
@Composable
internal fun UnitsSection(
    uiState: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier = modifier) {
    ChoiceRow(
        title = stringResource(R.string.settings_group_speed),
        options =
            listOf(
                SpeedUnitSetting.AUTO to stringResource(R.string.settings_option_auto),
                SpeedUnitSetting.KILOMETERS to stringResource(R.string.settings_speed_km),
                SpeedUnitSetting.MILES to stringResource(R.string.settings_speed_mi),
            ),
        selected = uiState.speedUnit,
        onSelect = { onAction(SettingsAction.SetSpeedUnit(it)) },
    )
    ChoiceRow(
        title = stringResource(R.string.settings_group_temperature),
        options =
            listOf(
                TemperatureUnitSetting.AUTO to stringResource(R.string.settings_option_auto),
                TemperatureUnitSetting.CELSIUS to stringResource(R.string.settings_temp_celsius),
                TemperatureUnitSetting.FAHRENHEIT to stringResource(R.string.settings_temp_fahrenheit),
            ),
        selected = uiState.temperatureUnit,
        onSelect = { onAction(SettingsAction.SetTemperatureUnit(it)) },
    )
    ChoiceRow(
        title = stringResource(R.string.settings_group_clock),
        options =
            listOf(
                ClockSetting.AUTO to stringResource(R.string.settings_option_auto),
                ClockSetting.TWELVE_HOUR to stringResource(R.string.settings_clock_12),
                ClockSetting.TWENTY_FOUR_HOUR to stringResource(R.string.settings_clock_24),
            ),
        selected = uiState.clock,
        onSelect = { onAction(SettingsAction.SetClock(it)) },
    )
    SwitchRow(
        title = stringResource(R.string.settings_group_clock_seconds),
        checked = uiState.showClockSeconds,
        onCheckedChange = { onAction(SettingsAction.SetShowClockSeconds(it)) },
    )
}
