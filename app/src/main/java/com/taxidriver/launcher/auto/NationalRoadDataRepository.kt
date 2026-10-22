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
import java.io.InputStream
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
    private val onUpdate: (pins: List<OsmMapSign>, limitKmh: Int?, usable: Boolean, source: String, reason: SpeedLimitDiagnostic) -> Unit,
    private val onRoadSpeedVisuals: (List<TaggedRoadSpeed>) -> Unit = {},
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val downloader = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val assets = context.applicationContext.assets
    private val store = File(context.applicationContext.filesDir, "vietnam-driving-data")
    private val database = File(store, "vietnam_road_data.db")
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.MINUTES)
        .build()
    private var started = false
    private var closed = false
    private var cacheSource = "CHƯA NẠP"
    private var downloadInProgress = false
    private var downloadAttemptMs = Long.MIN_VALUE
    private var lastLookupMs = 0L
    private var busy = false
    private var latestRequest: Query? = null
    private var sqlite: SQLiteDatabase? = null

    private data class Query(
        val lat: Double, val lon: Double,
        val bearing: Double?, val reliableRoadNames: List<String>,
        val gpsAccuracyMeters: Float?,
    )
    private data class NearbySpeed(val distance: Double, val speedKmh: Int)
    private data class RoadVector(val distance: Double, val heading: Double)

    /**
     * Bundle first: GPS can query verified data without internet access.
     * Refresh on a separate thread so network cannot block SQLite queries.
     */
    fun start() {
        if (closed) return
        if (!started) {
            started = true
            executor.execute {
                try {
                    store.mkdirs()
                    openCachedDatabase()
                    if (sqlite == null) installBundledAsset()
                    if (sqlite != null) {
                        latestRequest?.let(::lookup)
                    } else {
                        cacheSource = "CHƯA NẠP"
                        notifyStatus()
                    }
                } catch (e: Exception) {
                    Log.w("TaxiDriverAuto", "Vietnam OSM packaged cache unavailable", e)
                    notifyStatus()
                }
            }
        }
        maybeCheckForUpdate()
    }

    fun maybeLookup(fix: Location, names: List<String>, trustedRoad: Boolean) {
        if (closed || !started || !fix.hasAccuracy() || fix.accuracy > 35f) return
        val now = SystemClock.elapsedRealtime()
        if (now - fix.elapsedRealtimeNanos / 1_000_000L !in 0L..6_000L) return
        val bearing = if (fix.hasBearing() &&
            fix.hasSpeed() && fix.speed >= 1.5f) fix.bearing.toDouble() else null
        latestRequest = Query(
            fix.latitude, fix.longitude, bearing,
            if (trustedRoad) names else emptyList(),
            if (fix.hasAccuracy()) fix.accuracy else null,
        )
        maybeCheckForUpdate()
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

    private fun notifyStatus() {
        val available = sqlite != null
        val mode = cacheSource
        handler.post { if (!closed) onUpdate(emptyList(), null, available, mode, SpeedLimitDiagnostic.CACHE_PENDING) }
    }

    private fun validated(path: File): Boolean {
        if (!path.isFile || path.length() <= 0L) return false
        val db = SQLiteDatabase.openDatabase(
            path.path, null, SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val schema = db.rawQuery("SELECT value FROM meta WHERE key='schema'", null)
                .use { it.moveToFirst() && it.getString(0) == "2" }
            if (!schema) return false
            return db.rawQuery("SELECT COUNT(*) FROM signs", null)
                .use { it.moveToFirst() && it.getInt(0) > 0 } &&
                db.rawQuery("SELECT COUNT(*) FROM speed_segments", null)
                .use { it.moveToFirst() && it.getInt(0) > 0 }
        } finally {
            db.close()
        }
    }

    private fun openCachedDatabase() {
        if (!database.isFile) return
        try {
            if (!validated(database)) return
            sqlite = SQLiteDatabase.openDatabase(
                database.path, null, SQLiteDatabase.OPEN_READONLY,
            )
            cacheSource = "CACHE"
        } catch (e: Exception) {
            Log.w("TaxiDriverAuto", "Vietnam OSM database invalid", e)
        }
    }

    private fun unpack(input: InputStream, target: File) {
        GZIPInputStream(input).use { gz ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(32_768)
                var total = 0L
                while (true) {
                    val n = gz.read(buffer)
                    if (n == -1) break
                    total += n
                    if (total > 300L * 1024L * 1024L) {
                        throw IllegalArgumentException("Vietnam OSM cache too large")
                    }
                    out.write(buffer, 0, n)
                }
                out.fd.sync()
            }
        }
    }

    private fun installFile(candidate: File, newSource: String) {
        if (!validated(candidate)) {
            throw IllegalArgumentException("Vietnam OSM SQLite cache verification failed")
        }
        val old = File(store, "vietnam_road_data.previous")
        old.delete()
        sqlite?.close()
        sqlite = null
        if (database.exists() && !database.renameTo(old)) {
            openCachedDatabase()
            throw IllegalStateException("Unable to preserve previous offline OSM cache")
        }
        try {
            if (!candidate.renameTo(database)) {
                candidate.copyTo(database, overwrite = true)
                candidate.delete()
            }
            openCachedDatabase()
            if (sqlite == null) throw IllegalStateException("Unable to open new OSM cache")
            cacheSource = newSource
            old.delete()
            if (latestRequest == null) notifyStatus()
            else latestRequest?.let(::lookup)
            Log.i("TaxiDriverAuto", "Vietnam OSM: $newSource cached database ready")
        } catch (e: Exception) {
            database.delete()
            if (old.exists()) old.renameTo(database)
            openCachedDatabase()
            notifyStatus()
            throw e
        }
    }

    private fun installBundledAsset() {
        val temp = File(store, "vietnam_road_data.bundled.next")
        try {
            assets.open("vietnam_road_data.osmcache").use { unpack(it, temp) }
            installFile(temp, "APK")
        } finally {
            temp.delete()
        }
    }

    private fun maybeCheckForUpdate() {
        if (closed || !started || downloadInProgress) return
        val now = SystemClock.elapsedRealtime()
        if (downloadAttemptMs != Long.MIN_VALUE &&
            now - downloadAttemptMs < 15L * 60L * 1_000L
        ) return
        // Avoid repeat downloads when a verified cache was installed recently.
        if (database.isFile &&
            System.currentTimeMillis() - database.lastModified() in
                0L..(24L * 60L * 60L * 1_000L)
        ) return
        downloadAttemptMs = now
        downloadInProgress = true
        downloader.execute {
            val url = "https://github.com/ThanhTaxiXanh/TaxiDriverLauncher/" +
                "releases/download/vietnam-osm-data/vietnam_road_data.db.gz"
            val tmp = File(store, "vietnam_road_data.remote.next")
            var downloaded = false
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", "TaxiDriverLauncher-AndroidAuto/3.0.5.1")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException(
                        "OSM database HTTP ${response.code}",
                    )
                    val body = response.body ?: throw IllegalStateException("Empty OSM download")
                    unpack(body.byteStream(), tmp)
                }
                downloaded = true
            } catch (e: Exception) {
                Log.w("TaxiDriverAuto", "Vietnam OSM update retry later", e)
                tmp.delete()
            } finally {
                executor.execute {
                    try {
                        if (downloaded && !closed) installFile(tmp, "ONLINE")
                    } catch (e: Exception) {
                        Log.w("TaxiDriverAuto", "Vietnam OSM remote dataset invalid", e)
                    } finally {
                        tmp.delete()
                        handler.post { downloadInProgress = false }
                    }
                }
            }
        }
    }

    private fun lookup(q: Query) {
        val db = sqlite ?: return
        // A tilted 3D screen sees well beyond the 500m driver-warning radius.
        // Fetch real OSM sign coordinates up to ~1.8km for the map only.
        val latSpan = 1_800.0 / 111_320.0
        val lonSpan = latSpan / cos(Math.toRadians(q.lat)).coerceAtLeast(0.2)
        val pins = mutableListOf<OsmMapSign>()
        val args = arrayOf(
            (q.lat - latSpan).toString(), (q.lat + latSpan).toString(),
            (q.lon - lonSpan).toString(), (q.lon + lonSpan).toString(),
        )
        try {
            db.rawQuery(
                "SELECT id,lat,lon,kind,speed_limit_kmh FROM signs " +
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
                    val signedSpeed = if (cursor.isNull(4)) null
                        else cursor.getInt(4).takeIf { it in 5..200 }
                    pins.add(OsmMapSign(cursor.getLong(0), kind, position, signedSpeed))
                }
            }
            val result = findSpeedLimit(db, q)
            val roads = nearbyTaggedRoads(db, q)
            handler.post {
                if (!closed) {
                    onUpdate(pins, result.limitKmh, true, cacheSource, result.reason)
                    onRoadSpeedVisuals(roads)
                }
            }
        } catch (e: Exception) {
            Log.w("TaxiDriverAuto", "Vietnam road query failed", e)
        }
    }

    /**
     * This is separate from the driver's current speed limit.
     * It displays only OSM highway ways with an unambiguous explicit
     * speed for both travel directions, without assigning it to the car.
     */
    private fun nearbyTaggedRoads(
        db: SQLiteDatabase,
        q: Query,
    ): List<TaggedRoadSpeed> {
        // Tagged roads visible toward the upper horizon of a 60-degree map.
        // This wider radius affects ONLY map annotation, never the car limit.
        val latSpan = 1_800.0 / 111_320.0
        val lonSpan = latSpan / cos(Math.toRadians(q.lat)).coerceAtLeast(0.2)
        val candidates = mutableListOf<TaggedRoadSpeed>()
        db.rawQuery(
            "SELECT way_id,name,maxspeed_kmh,forward_kmh,backward_kmh,geometry " +
                "FROM speed_segments WHERE minlat<=? AND maxlat>=? " +
                "AND minlon<=? AND maxlon>=? LIMIT 400",
            arrayOf(
                (q.lat + latSpan).toString(), (q.lat - latSpan).toString(),
                (q.lon + lonSpan).toString(), (q.lon - lonSpan).toString(),
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val limit = TaggedRoadSpeedPolicy.commonLimit(
                    cursor.getNullableInt(2),
                    cursor.getNullableInt(3),
                    cursor.getNullableInt(4),
                ) ?: continue
                val rawGeometry = JSONArray(cursor.getString(5))
                if (rawGeometry.length() !in 2..900) continue
                val points = (0 until rawGeometry.length()).mapNotNull { index ->
                    val coords = rawGeometry.optJSONArray(index) ?: return@mapNotNull null
                    val lat = coords.optDouble(0)
                    val lon = coords.optDouble(1)
                    if (!lat.isFinite() || !lon.isFinite()) null else RoadCoordinate(lat, lon)
                }
                if (points.size < 2) continue
                candidates.add(TaggedRoadSpeed(
                    wayId = cursor.getLong(0),
                    name = cursor.getString(1) ?: "",
                    limitKmh = limit,
                    coordinate = points.first(),
                    geometry = points,
                ))
            }
        }
        return TaggedRoadSpeedPolicy.bounded(
            RoadCoordinate(q.lat, q.lon), candidates, 1_800.0, 65,
        )
    }

    /** First find all tagged OSM ways spatially; pure logic verifies bearing,
     * road-name agreement or precise unambiguous GPS geometry.
     */
    private fun findSpeedLimit(db: SQLiteDatabase, q: Query): SpeedLimitMatch {
        val latSpan = 75.0 / 111_320.0
        val lonSpan = latSpan / cos(Math.toRadians(q.lat)).coerceAtLeast(0.2)
        val candidates = mutableListOf<OsmSpeedCandidate>()
        db.rawQuery(
            "SELECT way_id,name,maxspeed_kmh,forward_kmh,backward_kmh,geometry " +
                "FROM speed_segments WHERE minlat<=? AND maxlat>=? " +
                "AND minlon<=? AND maxlon>=? LIMIT 1500",
            arrayOf(
                (q.lat + latSpan).toString(), (q.lat - latSpan).toString(),
                (q.lon + lonSpan).toString(), (q.lon - lonSpan).toString(),
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val nearest = nearestSegment(q, JSONArray(cursor.getString(5))) ?: continue
                if (nearest.distance > 28.0) continue
                candidates.add(
                    OsmSpeedCandidate(
                        cursor.getLong(0),
                        nearest.distance, nearest.heading, cursor.getString(1),
                        cursor.getNullableInt(3), cursor.getNullableInt(4),
                        cursor.getNullableInt(2),
                    ),
                )
            }
        }
        return SpeedGeometryMatcher.match(
            candidates, q.reliableRoadNames, q.bearing, q.gpsAccuracyMeters,
        )
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
        downloader.shutdownNow()
    }
}
