package com.taxidriver.launcher.ui.home

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taxidriver.launcher.data.display.DockPosition
import com.taxidriver.launcher.data.display.DockWidth
import com.taxidriver.launcher.data.display.DriverSide
import com.taxidriver.launcher.data.display.MotionTier
import com.taxidriver.launcher.ui.home.components.DockConfig
import com.taxidriver.launcher.ui.home.components.GlassConfig
import com.taxidriver.launcher.ui.home.components.MapConfig
import com.taxidriver.launcher.ui.home.components.PanelVisibility
import com.taxidriver.launcher.ui.locale.SpeedUnit
import com.taxidriver.launcher.ui.locale.TemperatureUnit

@Composable
internal fun HomeRoute(
    is24Hour: Boolean,
    showClockSeconds: Boolean,
    speedUnit: SpeedUnit,
    temperatureUnit: TemperatureUnit,
    mapConfig: MapConfig,
    panels: PanelVisibility,
    glassConfig: GlassConfig,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
    dockPosition: DockPosition = DockPosition.BOTTOM,
    dockWidth: DockWidth = DockWidth.COMPACT,
    dockConfig: DockConfig = DockConfig(),
    driverSide: DriverSide = DriverSide.RIGHT,
    musicShowAlbum: Boolean = true,
    musicShowArt: Boolean = true,
    motionTier: MotionTier = MotionTier.STANDARD,
    sheetOpen: Boolean = false,
    fullscreen: Boolean = false,
) {
    val context = LocalContext.current
    val viewModel: HomeViewModel =
        viewModel(factory = HomeViewModelFactory(context.applicationContext as Application))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val updatePrompt by viewModel.updatePrompt.collectAsStateWithLifecycle()
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> currentOnEvent(event) }
    }
    HomeScreen(
        uiState = uiState,
        is24Hour = is24Hour,
        showClockSeconds = showClockSeconds,
        speedUnit = speedUnit,
        temperatureUnit = temperatureUnit,
        mapConfig = mapConfig,
        panels = panels,
        glassConfig = glassConfig,
        onAction = viewModel::onAction,
        modifier = modifier,
        dockPosition = dockPosition,
        dockWidth = dockWidth,
        dockConfig = dockConfig,
        driverSide = driverSide,
        musicShowAlbum = musicShowAlbum,
        musicShowArt = musicShowArt,
        spectrum = viewModel.audioSpectrum,
        motionTier = motionTier,
        updatePrompt = updatePrompt,
        sheetOpen = sheetOpen,
        fullscreen = fullscreen,
    )
}
