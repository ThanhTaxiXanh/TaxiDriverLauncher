package com.taxidriver.launcher.auto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.Style
import com.mapbox.maps.extension.androidauto.MapboxCarMap
import com.mapbox.maps.extension.androidauto.MapboxCarMapObserver
import com.mapbox.maps.extension.androidauto.MapboxCarMapSurface
import com.mapbox.maps.extension.androidauto.mapboxMapInstaller

/**
 * Experimental Mapbox maps-only Android Auto screen.
 * It does not provide routes, instructions or turn-by-turn navigation.
 */
class TaxiDriverCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = DriverSession()
}

private class DriverSession : Session() {
    private val mapboxCarMap: MapboxCarMap = mapboxMapInstaller()
        .install { context -> MapInitOptions(context) }

    override fun onCreateScreen(intent: Intent): Screen = DriverMapScreen(mapboxCarMap)
}

private class DriverMapScreen(private val carMap: MapboxCarMap) :
    Screen(carMap.carContext), DefaultLifecycleObserver {
    private val locationManager =
        carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var attachedSurface: MapboxCarMapSurface? = null
    private var latestLocation: Location? = null
    private var tracking = false

    private val mapObserver = object : MapboxCarMapObserver {
        override fun onAttached(mapboxCarMapSurface: MapboxCarMapSurface) {
            attachedSurface = mapboxCarMapSurface
            mapboxCarMapSurface.mapSurface.getMapboxMap().loadStyleUri(Style.DARK)
            latestLocation?.let(::updateCamera)
        }

        override fun onDetached(mapboxCarMapSurface: MapboxCarMapSurface) {
            if (attachedSurface === mapboxCarMapSurface) attachedSurface = null
        }
    }

    private val gpsListener = LocationListener { location ->
        if (!location.isFromMockProvider && location.accuracy <= 100f) {
            latestLocation = location
            updateCamera(location)
        }
    }

    init {
        mapboxMapInstaller(carMap).onResumed(mapObserver).install()
        lifecycle.addObserver(this)
    }

    private fun updateCamera(location: Location) {
        attachedSurface?.mapSurface?.getMapboxMap()?.setCamera(
            CameraOptions.Builder()
                .center(Point.fromLngLat(location.longitude, location.latitude))
                .zoom(17.5)
                .bearing(if (location.hasBearing()) location.bearing.toDouble() else 0.0)
                .pitch(40.0)
                .build(),
        )
    }

    override fun onStart(owner: LifecycleOwner) {
        val fine = carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = carContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse || tracking) return
        try {
            val provider = when {
                fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                else -> return
            }
            locationManager.getLastKnownLocation(provider)?.let {
                latestLocation = it
                updateCamera(it)
            }
            locationManager.requestLocationUpdates(provider, 500L, 0f, gpsListener, Looper.getMainLooper())
            tracking = true
        } catch (_: SecurityException) {
            tracking = false
        } catch (_: IllegalArgumentException) {
            tracking = false
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (tracking) locationManager.removeUpdates(gpsListener)
        tracking = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (tracking) locationManager.removeUpdates(gpsListener)
        tracking = false
        lifecycle.removeObserver(this)
    }

    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(Action.BACK).build())
        .build()
}
