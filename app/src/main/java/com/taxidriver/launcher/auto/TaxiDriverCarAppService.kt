package com.taxidriver.launcher.auto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPS-following Android Auto surface prototype.
 *
 * The surface paints the live GPS position and heading, NOT road map tiles or
 * turn-by-turn guidance. Real road map rendering requires a separate map
 * renderer and approved tile/data provider. Camera zoom remains fixed at 17.5.
 */
class TaxiDriverCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = DriverMapScreen(carContext)
    }
}

private class DriverMapScreen(context: androidx.car.app.CarContext) : Screen(context), DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val renderer = DriverSurface()
    private var current: Location? = null
    private var active = false
    private val listener = LocationListener { location ->
        if (location.accuracy > 100f || location.elapsedRealtimeNanos <= 0L) return@LocationListener
        current = location
        renderer.location = location
        renderer.draw()
    }

    init {
        lifecycle.addObserver(this)
        context.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (active) return
        val fine = carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = carContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            renderer.draw()
            return
        }
        try {
            val provider = when {
                fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> return
            }
            active = true
            locationManager.requestLocationUpdates(provider, 500L, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            active = false
        } catch (_: IllegalArgumentException) {
            active = false
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (active) locationManager.removeUpdates(listener)
        active = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (active) locationManager.removeUpdates(listener)
        active = false
        renderer.destroy()
        lifecycle.removeObserver(this)
    }

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(Action.BACK).build())
            .build()
}

private class DriverSurface : SurfaceCallback {
    private var container: SurfaceContainer? = null
    var location: Location? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zoom = 17.5

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        draw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
    }

    fun destroy() {
        container = null
    }

    fun draw() {
        val surface = container?.surface ?: return
        if (!surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()
            canvas.drawColor(Color.rgb(18, 28, 37))
            paint.color = Color.rgb(38, 56, 68)
            paint.strokeWidth = 1f
            val gap = (48f * (zoom / 17.5).toFloat())
            var x = 0f
            while (x < width) {
                canvas.drawLine(x, 0f, x, height, paint)
                x += gap
            }
            var y = 0f
            while (y < height) {
                canvas.drawLine(0f, y, width, y, paint)
                y += gap
            }
            val cx = width / 2f
            val cy = height * 0.62f
            paint.color = Color.rgb(42, 162, 242)
            canvas.drawCircle(cx, cy, 22f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, 15f, paint)
            paint.color = Color.rgb(0, 109, 205)
            val bearing = (location?.bearing ?: 0f) * Math.PI / 180.0
            val dx = sin(bearing).toFloat()
            val dy = -cos(bearing).toFloat()
            val arrow = Path().apply {
                moveTo(cx + dx * 29f, cy + dy * 29f)
                lineTo(cx - dx * 11f - dy * 13f, cy - dy * 11f + dx * 13f)
                lineTo(cx - dx * 11f + dy * 13f, cy - dy * 11f - dx * 13f)
                close()
            }
            canvas.drawPath(arrow, paint)
            paint.color = Color.WHITE
            paint.textSize = 30f
            canvas.drawText("GPS · Zoom 17.5", 24f, 45f, paint)
            paint.textSize = 22f
            val pos = location
            val message = if (pos == null) "Waiting for GPS / location permission" else
                "%.5f, %.5f · %.0f km/h".format(java.util.Locale.US, pos.latitude, pos.longitude, pos.speed * 3.6)
            canvas.drawText(message, 24f, 80f, paint)
            paint.textSize = 17f
            canvas.drawText("GPS surface preview — road map not enabled", 24f, height - 26f, paint)
        } catch (_: IllegalArgumentException) {
            // The host can replace the surface between validity check and lock.
        } catch (_: IllegalStateException) {
            // The host surface is no longer drawable.
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (_: IllegalStateException) {
                    // Surface was destroyed while painting.
                }
            }
        }
    }
}
