package com.taxidriver.launcher.auto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.Geocoder
import android.os.Handler
import java.util.Locale
import java.time.LocalTime
import java.time.ZoneId
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarToast
import android.widget.Toast
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.BlurMaskFilter
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
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.Style
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.extension.style.style
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.maps.extension.style.image.image
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconPitchAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.IconRotationAlignment
import com.mapbox.maps.extension.style.layers.generated.fillExtrusionLayer
import com.mapbox.maps.extension.style.expressions.dsl.generated.get
import com.mapbox.maps.extension.style.expressions.dsl.generated.literal
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

@OptIn(MapboxExperimental::class)
private class DriverMapScreen(private val carMap: MapboxCarMap) :
    Screen(carMap.carContext), DefaultLifecycleObserver {
    private val locationManager =
        carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var attachedSurface: MapboxCarMapSurface? = null
    private var latestLocation: Location? = null
    private val registeredProviders = mutableSetOf<String>()
    private var tracking = false
    private var lastBearing = 0.0
    private var previousFix: Location? = null
    private val gpsSpeedFilter = GpsSpeedFilter()
    private var speedWidget: DrivingSpeedWidget? = null
    private var speedRefreshActive = false
    private val speedRefresh = object : Runnable {
        override fun run() {
            refreshSpeedHud()
            if (speedRefreshActive) mainHandler.postDelayed(this, 1_000L)
        }
    }
    private var styleReady = false
    private var cameraInitialized = false
    private var gpsState = "Waiting for GPS"
    private var lastStatusShown: String? = null
    private var lastAcceptedElapsedNanos = 0L
    private var roadName: String? = null
    private var roadLookupLocation: Location? = null
    private val roadGeocoder by lazy { Geocoder(carContext, Locale("vi", "VN")) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val roadSourceId = "taxi-driver-street-chip"
    private val roadLayerId = "taxi-driver-street-label"
    private var roadRequestId = 0
    private var activeNightMode: Boolean? = null
    private val vietnamTime = ZoneId.of("Asia/Ho_Chi_Minh")
    private val themeCheck = object : Runnable {
        override fun run() {
            refreshThemeIfNeeded()
            mainHandler.postDelayed(this, 30_000L)
        }
    }
    private fun isNightInVietnam(): Boolean {
        val hour = LocalTime.now(vietnamTime).hour
        return hour < 6 || hour >= 18
    }
    private fun refreshThemeIfNeeded() {
        val surface = attachedSurface ?: return
        val night = isNightInVietnam()
        if (activeNightMode != night) loadDrivingStyle(surface)
    }
    private val markerBitmap by lazy { createVehicleMarker() }
    private val markerId = "taxi-driver-location"
    private val sourceId = "taxi-driver-gps-source"
    private val layerId = "taxi-driver-gps-layer"

    private val mapObserver = object : MapboxCarMapObserver {
        override fun onAttached(mapboxCarMapSurface: MapboxCarMapSurface) {
            attachedSurface = mapboxCarMapSurface
            // A MapSurface widget is independent of the tilted/rotating map.
            // Keep the display reserved for GPS speed only.
            val widget = DrivingSpeedWidget(isNightInVietnam())
            speedWidget = widget
            mapboxCarMapSurface.mapSurface.addWidget(widget)
            refreshSpeedHud()
            loadDrivingStyle(mapboxCarMapSurface)
        }

        override fun onDetached(mapboxCarMapSurface: MapboxCarMapSurface) {
            if (attachedSurface === mapboxCarMapSurface) {
                speedWidget?.let(mapboxCarMapSurface.mapSurface::removeWidget)
                speedWidget = null
                attachedSurface = null
                styleReady = false
                cameraInitialized = false
            }
        }
    }

    /** Recreates custom sources/layers on every light/dark style transition. */
    private fun loadDrivingStyle(surface: MapboxCarMapSurface) {
        val night = isNightInVietnam()
        activeNightMode = night
        styleReady = false
        cameraInitialized = false
        surface.mapSurface.getMapboxMap().loadStyle(
            style(if (night) Style.DARK else Style.MAPBOX_STREETS) {
                +image(markerId, markerBitmap)
                    // An empty source prevents a phantom marker at the equator.
                    +geoJsonSource(sourceId) {
                        featureCollection(FeatureCollection.fromFeatures(emptyList<Feature>()))
                    }
                    +fillExtrusionLayer("taxi-driver-buildings-3d", "composite") {
                        sourceLayer("building")
                        minZoom(15.0)
                        fillExtrusionColor(if (isNightInVietnam()) "#68788a" else "#aab8c8")
                        fillExtrusionOpacity(0.43)
                        fillExtrusionHeight(7.0)
                        fillExtrusionBase(0.0)
                    }
                    +geoJsonSource(roadSourceId) {
                        featureCollection(FeatureCollection.fromFeatures(emptyList<Feature>()))
                    }
                    +symbolLayer(roadLayerId, roadSourceId) {
                        textField(get { literal("name") })
                        textSize(14.0)
                        textColor(if (isNightInVietnam()) "#ffffff" else "#152e4b")
                        textHaloColor(if (isNightInVietnam()) "#17304a" else "#ffffff")
                        textHaloWidth(2.5)
                        textOffset(listOf(0.0, 4.0))
                        textAllowOverlap(true)
                    }
                    // The puck is declared last so it paints over 3D buildings.
                    +symbolLayer(layerId, sourceId) {
                        iconImage(markerId)
                        iconAllowOverlap(true)
                        iconPitchAlignment(IconPitchAlignment.MAP)
                        iconRotationAlignment(IconRotationAlignment.MAP)
                        iconRotate(0.0)
                    }
            },
        ) {
            if (attachedSurface === surface && activeNightMode == night) {
                styleReady = true
                Log.i("TaxiDriverAuto", "Mapbox style ready: night=$night")
                latestLocation?.let(::updateCamera)
                updateRoadLabel()
            }
        }
    }

    private val gpsListener = LocationListener { location ->
        if (acceptFix(location)) {
            previousFix = location
            latestLocation = location
            if (gpsState != "GPS active") reportStatus("GPS active")
            lastAcceptedElapsedNanos = SystemClock.elapsedRealtimeNanos()
            gpsSpeedFilter.accept(
                if (location.hasSpeed()) location.speed else null,
                if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
                location.elapsedRealtimeNanos / 1_000_000L,
            )
            refreshSpeedHud()
            updateCamera(location)
            maybeLookupRoad(location)
        }
    }

    init {
        mapboxMapInstaller(carMap).onResumed(mapObserver).install()
        lifecycle.addObserver(this)
        mainHandler.post(themeCheck)
    }

    private fun refreshSpeedHud() {
        speedWidget?.update(gpsSpeedFilter.kmh(SystemClock.elapsedRealtime()), isNightInVietnam())
    }

    private fun startSpeedRefresh() {
        if (speedRefreshActive) return
        speedRefreshActive = true
        mainHandler.post(speedRefresh)
    }

    private fun stopSpeedRefresh() {
        speedRefreshActive = false
        mainHandler.removeCallbacks(speedRefresh)
        gpsSpeedFilter.reset()
        refreshSpeedHud()
    }

    /** Reject stale or implausible location data before it reaches the map. */
    private fun acceptFix(fix: Location): Boolean {
        if (fix.isFromMockProvider) return false
        val old = previousFix
        val delta = old?.let {
            (fix.elapsedRealtimeNanos - it.elapsedRealtimeNanos) / 1_000_000_000.0
        }
        val distance = old?.distanceTo(fix)
        val valid = GpsFixPolicy.isAcceptable(
            if (fix.hasAccuracy()) fix.accuracy else null,
            fix.latitude,
            fix.longitude,
            (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L,
            delta,
            distance,
        )
        if (!valid) Log.d("TaxiDriverAuto", "Rejected invalid or stale location fix")
        return valid
    }

    private fun updateCamera(location: Location) {
        if (!styleReady) return
        val map = attachedSurface?.mapSurface?.getMapboxMap() ?: return
        val point = Point.fromLngLat(location.longitude, location.latitude)
        // Update marker only when the style is loaded and this is a valid fix.
        map.getStyle()?.getSourceAs<GeoJsonSource>(sourceId)?.geometry(point)
        updateRoadLabel()
        // GPS bearing is unreliable while stationary. Do not reset to north at stops.
        if (location.hasBearing() && location.speed >= 1.5f) {
            val target = location.bearing.toDouble()
            val delta = ((target - lastBearing + 540.0) % 360.0) - 180.0
            lastBearing = (lastBearing + delta * 0.3 + 360.0) % 360.0
        }
        // Target position: 70% screen height with an asymmetric camera viewport.
        // Mapbox positioning depends on the connected host viewport dimensions.
        val height = attachedSurface?.surfaceContainer?.height?.toDouble() ?: 0.0
        val offset = (height * 0.40).coerceAtLeast(0.0)
        // A map-aligned icon is oriented to geographical north. Rotate it to
        // the filtered vehicle bearing; the map camera uses that same bearing.
        // Map-aligned icon rotation is geographical; use the filtered vehicle
        // bearing directly so it aligns with the camera-follow heading.
        map.getStyle()?.setStyleLayerProperty(
            layerId, "icon-rotate", com.mapbox.bindgen.Value(lastBearing),
        )
        map.setCamera(
            CameraOptions.Builder()
                .center(point)
                .padding(EdgeInsets(offset, 0.0, 0.0, 0.0))
                .zoom(18.0)
                .bearing(lastBearing)
                .pitch(60.0)
                .build(),
        )
        if (!cameraInitialized) {
            cameraInitialized = true
            Log.i("TaxiDriverAuto", "Initial camera locked to GPS; zoom=18.0")
        }
    }

    /** Reverse-geocode only after meaningful movement, not once per GPS frame. */
    private fun maybeLookupRoad(location: Location) {
        val previous = roadLookupLocation
        if (previous != null && previous.distanceTo(location) < 45f) return
        roadLookupLocation = Location(location)
        if (!Geocoder.isPresent()) return
        val request = ++roadRequestId
        roadGeocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
            mainHandler.post {
                if (request != roadRequestId) return@post
                val address = addresses.firstOrNull()
                val result = address?.thoroughfare ?: address?.subLocality
                if (!result.isNullOrBlank() && roadName != result) {
                    roadName = result
                    updateRoadLabel()
                }
            }
        }
    }

    private fun updateRoadLabel() {
        if (!styleReady) return
        val fix = latestLocation ?: return
        val name = roadName ?: return
        val feature = Feature.fromGeometry(Point.fromLngLat(fix.longitude, fix.latitude))
        feature.addStringProperty("name", name)
        attachedSurface?.mapSurface?.getMapboxMap()?.getStyle()
            ?.getSourceAs<GeoJsonSource>(roadSourceId)
            ?.featureCollection(FeatureCollection.fromFeature(feature))
    }

    /** Compact road-level Chevron: white border, blue fill, subtle soft shadow. */
    private fun createVehicleMarker(): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val chevron = Path().apply {
            moveTo(64f, 12f)
            cubicTo(60f, 12f, 56f, 16f, 52f, 22f)
            lineTo(19f, 87f)
            quadTo(14f, 99f, 27f, 104f)
            quadTo(33f, 106f, 39f, 101f)
            lineTo(64f, 83f)
            lineTo(89f, 101f)
            quadTo(95f, 106f, 101f, 104f)
            quadTo(114f, 99f, 109f, 87f)
            lineTo(76f, 22f)
            cubicTo(72f, 16f, 68f, 12f, 64f, 12f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
            style = Paint.Style.FILL
            setShadowLayer(5f, 0f, 4f, Color.argb(125, 0, 0, 0))
        }
        canvas.save()
        canvas.translate(0f, 4f)
        canvas.drawPath(chevron, paint)
        canvas.restore()
        paint.clearShadowLayer()
        paint.color = Color.WHITE
        canvas.drawPath(chevron, paint)
        paint.color = Color.rgb(43, 146, 244)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 2f
        // Inner blue chevron preserves a high-contrast white outline.
        val inside = Path().apply {
            moveTo(64f, 24f)
            lineTo(27f, 94f)
            lineTo(64f, 71f)
            lineTo(101f, 94f)
            close()
        }
        paint.style = Paint.Style.FILL
        canvas.drawPath(inside, paint)
        return bitmap
    }

    private fun reportStatus(status: String) {
        gpsState = status
        Log.i("TaxiDriverAuto", "GPS status: $status")
        if (lastStatusShown != status) {
            lastStatusShown = status
            CarToast.makeText(carContext, status, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        startSpeedRefresh()
        startTracking()
    }

    override fun onResume(owner: LifecycleOwner) {
        startSpeedRefresh()
        // A projected Android Auto screen can attach its map after the service
        // starts; retry registration when the screen becomes interactive.
        startTracking()
        refreshThemeIfNeeded()
        latestLocation?.let(::updateCamera)
    }

    private fun startTracking() {
        val fine = carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = carContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            reportStatus("Taxi Driver: grant location permission on phone")
            return
        }
        if (tracking) return
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { provider ->
                    (provider != LocationManager.GPS_PROVIDER || fine) &&
                        locationManager.isProviderEnabled(provider)
                }
            if (providers.isEmpty()) {
                reportStatus("Taxi Driver: enable device location")
                return
            }
            // Start listeners FIRST. Last-known positions can be stale and must
            // never stop or postpone acquisition of a fresh fix.
            for (provider in providers) {
                locationManager.requestLocationUpdates(
                    provider, 500L, 0f, gpsListener, Looper.getMainLooper(),
                )
                registeredProviders.add(provider)
            }
            tracking = registeredProviders.isNotEmpty()
            reportStatus("Taxi Driver: searching GPS")
            providers.mapNotNull(locationManager::getLastKnownLocation)
                .sortedByDescending(Location::getElapsedRealtimeNanos)
                .firstOrNull(::acceptFix)?.let { fix ->
                    previousFix = fix
                    latestLocation = fix
                    gpsSpeedFilter.accept(
                        if (fix.hasSpeed()) fix.speed else null,
                        if (fix.hasSpeedAccuracy()) fix.speedAccuracyMetersPerSecond else null,
                        fix.elapsedRealtimeNanos / 1_000_000L,
                    )
                    refreshSpeedHud()
                    updateCamera(fix)
                }
        } catch (exception: SecurityException) {
            stopTracking()
            reportStatus("Taxi Driver: location permission denied")
            Log.w("TaxiDriverAuto", "Location registration denied", exception)
        } catch (exception: IllegalArgumentException) {
            stopTracking()
            reportStatus("Taxi Driver: GPS provider unavailable")
            Log.w("TaxiDriverAuto", "Location provider unavailable", exception)
        }
    }

    private fun stopTracking() {
        if (registeredProviders.isNotEmpty()) {
            locationManager.removeUpdates(gpsListener)
            registeredProviders.clear()
        }
        tracking = false
    }

    override fun onStop(owner: LifecycleOwner) {
        stopSpeedRefresh()
        stopTracking()
        if (lastAcceptedElapsedNanos != 0L) gpsState = "GPS paused"
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopTracking()
        stopSpeedRefresh()
        mainHandler.removeCallbacks(themeCheck)
        lifecycle.removeObserver(this)
    }

    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(Action.BACK).build())
        .build()
}
