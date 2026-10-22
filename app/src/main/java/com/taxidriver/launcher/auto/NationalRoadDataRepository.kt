package com.taxidriver.launcher.auto

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Offline, indexed national OSM dataset produced by GitHub Actions.
 * The JSON exports are human/auditor-facing; this SQLite sidecar enables
 * safe, local radius queries without loading national JSON into Android RAM.
 */
internal class NationalRoadDataRepository(
    context: Context,
    private val onUpdate: (pins: List<OsmMapSign>, limitKmh: Int?, usable: Boolean) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val store = File(context.applicationContext.filesDir, "vietnam-driving-data")
    private val database = File(store, "vietnam_road_data.db")
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.MINUTES)
        .build()
    private var started = false
    private var closed = false
    private var downloadAttemptMs = Long.MIN_VALUE
    private var lastLookupMs = 0L
    private var busy = false
    private var latestRequest: Query? = null
    private var sqlite: SQLiteDatabase? = null

    private data class Query(
        val lat: Double, val lon: Double,
        val bearing: Double?, val reliableRoadNames: List<String>,
    )
    private data class NearbySpeed(val distance: Double, val speedKmh: Int)
    private data class RoadVector(val distance: Double, val heading: Double)

    fun start() {
        if (closed || started) return
        started = true
        executor.execute {
            try {
                store.mkdirs()
                openCachedDatabase()
                refreshFromReleaseIfNeeded()
                latestRequest?.let(::lookup)
            } catch (e: Exception) {
                Log.w("TaxiDriverAuto", "Vietnam offline road data unavailable", e)
            }
        }
    }

    fun maybeLookup(fix: Location, names: List<String>, trustedRoad: Boolean) {
        if (closed || !started || !fix.hasAccuracy() || fix.accuracy > 35f) return
        val now = SystemClock.elapsedRealtime()
        if (now - fix.elapsedRealtimeNanos / 1_000_000L !in 0L..6_000L) return
        val bearing = if (trustedRoad && fix.hasBearing() &&
            fix.hasSpeed() && fix.speed >= 1.5f) fix.bearing.toDouble() else null
        latestRequest = Query(
            fix.latitude, fix.longitude, bearing,
            if (trustedRoad) names else emptyList(),
        )
        if (busy || now - lastLookupMs < 3_000L) return
        lastLookupMs = now
        busy = true
        executor.execute {
            try {
                latestRequest?.let(::lookup)
            } finally {
                handler.post { busy = false }
            }
        }
    }

    private fun openCachedDatabase() {
        if (!database.isFile) return
        try {
            val db = SQLiteDatabase.openDatabase(
                database.path, null, SQLiteDatabase.OPEN_READONLY,
            )
            val valid = db.rawQuery("SELECT value FROM meta WHERE key='schema'", null)
                .use { it.moveToFirst() && it.getString(0) == "1" }
            if (!valid) {
                db.close()
                return
            }
            sqlite = db
        } catch (e: Exception) {
            Log.w("TaxiDriverAuto", "Cached Vietnam OSM database invalid", e)
        }
    }

    private fun refreshFromReleaseIfNeeded() {
        if (closed) return
        // Previously downloaded database remains available offline.
        if (sqlite != null && System.currentTimeMillis() - database.lastModified() <
            14L * 24L * 60L * 60L * 1000L
        ) return
        val now = SystemClock.elapsedRealtime()
        if (downloadAttemptMs != Long.MIN_VALUE &&
            now - downloadAttemptMs < 60L * 60L * 1000L
        ) return
        downloadAttemptMs = now
        val url = "https://github.com/ThanhTaxiXanh/TaxiDriverLauncher/" +
            "releases/download/vietnam-osm-data/vietnam_road_data.db.gz"
        val request = Request.Builder().url(url)
            .header("User-Agent", "TaxiDriverLauncher-AndroidAuto/3.0.5")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body ?: return
                val tmp = File(store, "vietnam_road_data.next")
                try {
                    GZIPInputStream(body.byteStream()).use { inp ->
                        FileOutputStream(tmp).use { out ->
                            val buffer = ByteArray(32_768)
                            var total = 0L
                            while (true) {
                                val n = inp.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > 300L * 1024L * 1024L) {
                                    throw IllegalStateException("Road cache exceeds 300 MiB")
                                }
                                out.write(buffer, 0, n)
                            }
                            out.fd.sync()
                        }
                    }
                    val candidate = SQLiteDatabase.openDatabase(
                        tmp.path, null, SQLiteDatabase.OPEN_READONLY,
                    )
                    val valid = candidate.rawQuery(
                        "SELECT value FROM meta WHERE key='schema'", null,
                    ).use { it.moveToFirst() && it.getString(0) == "1" }
                    candidate.close()
                    if (!valid) throw IllegalArgumentException("Unknown road data schema")
                    sqlite?.close()
                    sqlite = null
                    if (!tmp.renameTo(database)) {
                        tmp.copyTo(database, overwrite = true)
                        tmp.delete()
                    }
                    openCachedDatabase()
                    Log.i("TaxiDriverAuto", "Vietnam road database cache refreshed")
                } finally {
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            // Network or release failures should not break the existing live
            // Mapbox matching, GPS speed HUD, or Overpass sign layer.
            Log.w("TaxiDriverAuto", "Vietnam OSM data download deferred", e)
        }
    }

    private fun lookup(q: Query) {
        val db = sqlite ?: return
        val latSpan = 550.0 / 111_320.0
        val lonSpan = latSpan / cos(Math.toRadians(q.lat)).coerceAtLeast(0.2)
        val pins = mutableListOf<OsmMapSign>()
        val args = arrayOf(
            (q.lat - latSpan).toString(), (q.lat + latSpan).toString(),
            (q.lon - lonSpan).toString(), (q.lon + lonSpan).toString(),
        )
        try {
            db.rawQuery(
                "SELECT id,lat,lon,kind FROM signs " +
                    "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 800",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val kind = try {
                        RoadSignKind.valueOf(cursor.getString(3))
                    } catch (_: IllegalArgumentException) {
                        RoadSignKind.OTHER
                    }
                    val position = RoadCoordinate(cursor.getDouble(1), cursor.getDouble(2))
                    pins.add(OsmMapSign(cursor.getLong(0), kind, position))
                }
            }
            val trustedLimit = if (q.bearing != null && q.reliableRoadNames.isNotEmpty()) {
                findSpeedLimit(db, q, latSpan, lonSpan)
            } else null
            handler.post {
                if (!closed) onUpdate(pins, trustedLimit, true)
            }
        } catch (e: Exception) {
            Log.w("TaxiDriverAuto", "Vietnam road query failed", e)
        }
    }

    private fun findSpeedLimit(
        db: SQLiteDatabase, q: Query, latSpan: Double, lonSpan: Double,
    ): Int? {
        val names = q.reliableRoadNames.map(::normalizeRoad).filter(String::isNotEmpty).toSet()
        if (names.isEmpty()) return null
        val options = mutableListOf<NearbySpeed>()
        db.rawQuery(
            "SELECT name,maxspeed_kmh,forward_kmh,backward_kmh,geometry " +
                "FROM speed_segments WHERE minlat<=? AND maxlat>=? AND minlon<=? " +
                "AND maxlon>=? LIMIT 300",
            arrayOf(
                (q.lat + latSpan).toString(), (q.lat - latSpan).toString(),
                (q.lon + lonSpan).toString(), (q.lon - lonSpan).toString(),
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (normalizeRoad(cursor.getString(0)) !in names) continue
                val coordinates = JSONArray(cursor.getString(4))
                val nearest = nearestSegment(q, coordinates) ?: continue
                if (nearest.distance > 18.0) continue
                val signedHeading = abs(
                    ((nearest.heading - q.bearing!! + 540.0) % 360.0) - 180.0,
                )
                val forward = signedHeading <= 32.0
                val backward = signedHeading >= 148.0
                if (!forward && !backward) continue
                val selected = if (forward) cursor.getNullableInt(2)
                    else cursor.getNullableInt(3)
                val limit = selected ?: cursor.getNullableInt(1) ?: continue
                if (limit !in 5..200) continue
                options.add(NearbySpeed(nearest.distance, limit))
            }
        }
        if (options.isEmpty()) return null
        val nearest = options.minOf { it.distance }
        // If conflicting matched roads at a junction, suppress the display.
        val close = options.filter { it.distance <= nearest + 7.0 }
        return close.map { it.speedKmh }.distinct().singleOrNull()
    }

    private fun android.database.Cursor.getNullableInt(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun nearestSegment(q: Query, points: JSONArray): RoadVector? {
        var result: RoadVector? = null
        val longitudeScale = 111_320.0 * cos(Math.toRadians(q.lat))
        for (i in 0 until points.length() - 1) {
            val a = points.optJSONArray(i) ?: continue
            val b = points.optJSONArray(i + 1) ?: continue
            val ax = (a.optDouble(1) - q.lon) * longitudeScale
            val ay = (a.optDouble(0) - q.lat) * 111_320.0
            val bx = (b.optDouble(1) - q.lon) * longitudeScale
            val by = (b.optDouble(0) - q.lat) * 111_320.0
            val dx = bx - ax
            val dy = by - ay
            val sq = dx * dx + dy * dy
            if (!sq.isFinite() || sq < 1.0) continue
            val t = ((-ax * dx - ay * dy) / sq).coerceIn(0.0, 1.0)
            val dist = hypot(ax + dx * t, ay + dy * t)
            if (result == null || dist < result.distance) {
                result = RoadVector(
                    dist, (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0,
                )
            }
        }
        return result
    }

    private fun normalizeRoad(s: String): String = s.trim()
        .lowercase(Locale.ROOT)
        .removePrefix("đường ")
        .removePrefix("phố ")
        .replace(Regex("\\s+"), " ")

    fun stop() {
        closed = true
        executor.execute {
            sqlite?.close()
            sqlite = null
        }
        executor.shutdown()
    }
}
