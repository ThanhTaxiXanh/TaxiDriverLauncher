package com.taxidriver.launcher.auto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.extension.style.style
import com.mapbox.maps.extension.style.image.image
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
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
    private var lastBearing = 0.0
    private var previousFix: Location? = null
    private var styleReady = false
    private val markerBitmap by lazy { createVehicleMarker() }
    private val markerId = "taxi-driver-location"
    private val sourceId = "taxi-driver-gps-source"
    private val layerId = "taxi-driver-gps-layer"

    private val mapObserver = object : MapboxCarMapObserver {
        override fun onAttached(mapboxCarMapSurface: MapboxCarMapSurface) {
            attachedSurface = mapboxCarMapSurface
            styleReady = false
            // Streets uses higher road/label contrast than the previous near-black DARK style.
            mapboxCarMapSurface.mapSurface.getMapboxMap().loadStyle(
                style(Style.MAPBOX_STREETS) {
                    +image(markerId, markerBitmap)
                    +geoJsonSource(sourceId) {
                        geometry(Point.fromLngLat(0.0, 0.0))
                    }
                    +symbolLayer(layerId, sourceId) {
                        iconImage(markerId)
                        iconAllowOverlap(true)
                    }
                },
            ) {
                if (attachedSurface === mapboxCarMapSurface) {
                    styleReady = true
                    latestLocation?.let(::updateCamera)
                }
            }
        }

        override fun onDetached(mapboxCarMapSurface: MapboxCarMapSurface) {
            if (attachedSurface === mapboxCarMapSurface) {
                attachedSurface = null
                styleReady = false
            }
        }
    }

    private val gpsListener = LocationListener { location ->
        if (acceptFix(location)) {
            previousFix = location
            latestLocation = location
            updateCamera(location)
        }
    }

    init {
        mapboxMapInstaller(carMap).onResumed(mapObserver).install()
        lifecycle.addObserver(this)
    }

    /** Reject old/out-of-order fixes, huge accuracy circles, and impossible jumps. */
    private fun acceptFix(fix: Location): Boolean {
        if (fix.isFromMockProvider || !fix.hasAccuracy() || fix.accuracy > 70f) return false
        if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) return false
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L
        if (ageMillis !in 0..10_000L) return false
        val old = previousFix ?: return true
        val dt = (fix.elapsedRealtimeNanos - old.elapsedRealtimeNanos) / 1_000_000_000.0
        if (dt <= 0.0) return false
        return old.distanceTo(fix) / dt < 75.0
    }

    private fun updateCamera(location: Location) {
        if (!styleReady) return
        val map = attachedSurface?.mapSurface?.getMapboxMap() ?: return
        val point = Point.fromLngLat(location.longitude, location.latitude)
        map.getStyle()?.getSourceAs<GeoJsonSource>(sourceId)?.geometry(point)
        // GPS bearing is unreliable while stationary. Do not reset to north at stops.
        if (location.hasBearing() && location.speed >= 1.5f) {
            val target = location.bearing.toDouble()
            val delta = ((target - lastBearing + 540.0) % 360.0) - 180.0
            lastBearing = (lastBearing + delta * 0.3 + 360.0) % 360.0
        }
        // Keep the location at 62% of the display height using viewport padding.
        // Mapbox positioning depends on the connected host viewport dimensions.
        val height = attachedSurface?.surfaceContainer?.height?.toDouble() ?: 0.0
        val offset = (height * 0.24).coerceAtLeast(0.0)
        map.setCamera(
            CameraOptions.Builder()
                .center(point)
                .padding(EdgeInsets(0.0, 0.0, offset, 0.0))
                .zoom(17.5)
                .bearing(lastBearing)
                .pitch(40.0)
                .build(),
        )
    }

    private fun createVehicleMarker(): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(7, 100, 200)
        canvas.drawCircle(48f, 48f, 37f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(48f, 48f, 32f, paint)
        paint.color = Color.rgb(0, 114, 235)
        canvas.drawPath(Path().apply {
            moveTo(48f, 10f)
            lineTo(73f, 76f)
            lineTo(48f, 63f)
            lineTo(23f, 76f)
            close()
        }, paint)
        return bitmap
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
                if (acceptFix(it)) {
                    previousFix = it
                    latestLocation = it
                    updateCamera(it)
                }
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
