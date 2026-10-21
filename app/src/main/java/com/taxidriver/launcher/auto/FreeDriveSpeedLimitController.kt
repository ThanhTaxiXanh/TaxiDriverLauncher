package com.taxidriver.launcher.auto

import android.content.Context
import android.util.Log
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.speed.model.SpeedUnit
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.common.location.Location

/**
 * Free drive observes road-matched speed limits only; it does not change the
 * existing Android GPS puck, camera, speed HUD, or request any destination.
 *
 * The Navigation SDK may not have map data for a particular Vietnamese road.
 * In that case notify null and show an unknown limit, never a guessed value.
 */
internal class FreeDriveSpeedLimitController(
    private val context: Context,
    private val onRoadMatch: (names: List<String>, reliable: Boolean) -> Unit,
    private val onLimit: (value: Int?, unit: RoadSpeedUnit, onRoad: Boolean, reliable: Boolean) -> Unit,
) {
    private var navigation: MapboxNavigation? = null
    private var ownsNavigation = false
    private var started = false
    private val observer = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) = Unit

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            onRoadMatch(
                locationMatcherResult.road.components.map { it.text }.filter { it.isNotBlank() },
                !locationMatcherResult.isOffRoad &&
                    !locationMatcherResult.isDegradedMapMatching &&
                    locationMatcherResult.roadEdgeMatchProbability >= 0.70f,
            )
            val speedLimit = locationMatcherResult.speedLimitInfo
            val unit = when (speedLimit.unit) {
                SpeedUnit.KILOMETERS_PER_HOUR -> RoadSpeedUnit.KMH
                SpeedUnit.MILES_PER_HOUR -> RoadSpeedUnit.MPH
                else -> RoadSpeedUnit.UNKNOWN
            }
            onLimit(
                speedLimit.speed, unit,
                !locationMatcherResult.isOffRoad,
                !locationMatcherResult.isDegradedMapMatching &&
                    locationMatcherResult.roadEdgeMatchProbability >= 0.5f,
            )
        }
    }

    fun start() {
        if (started) return
        try {
            ownsNavigation = !MapboxNavigationProvider.isCreated()
            val nav = if (ownsNavigation) {
                MapboxNavigationProvider.create(
                    NavigationOptions.Builder(context.applicationContext).build(),
                )
            } else {
                MapboxNavigationProvider.retrieve()
            }
            // Track ownership before registering, so failures still clean up.
            navigation = nav
            nav.registerLocationObserver(observer)
            nav.startTripSession()
            started = true
        } catch (e: Exception) {
            Log.w("TaxiDriverAuto", "Free-drive map matching unavailable", e)
            onLimit(null, RoadSpeedUnit.UNKNOWN, false, false)
            stop()
        }
    }

    fun stop() {
        val nav = navigation
        if (nav != null) {
            nav.unregisterLocationObserver(observer)
            if (ownsNavigation) {
                nav.stopTripSession()
                MapboxNavigationProvider.destroy()
            }
        }
        navigation = null
        ownsNavigation = false
        started = false
        onRoadMatch(emptyList(), false)
        onLimit(null, RoadSpeedUnit.UNKNOWN, false, false)
    }
}
