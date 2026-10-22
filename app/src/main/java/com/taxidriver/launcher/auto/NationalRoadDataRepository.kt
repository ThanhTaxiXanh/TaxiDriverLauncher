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
    private val onUpdate: (pins: List<OsmMapSign>, limitKmh: Int?, usable: Boolean, source: String) -> Unit,
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
                        notifyStatus()
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
        val bearing = if (trustedRoad && fix.hasBearing() &&
            fix.hasSpeed() && fix.speed >= 1.5f) fix.bearing.toDouble() else null
        latestRequest = Query(
            fix.latitude, fix.longitude, bearing,
            if (trustedRoad) names else emptyList(),
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
        handler.post { if (!closed) onUpdate(emptyList(), null, available, mode) }
    }

    private fun validated(path: File): Boolean {
        if (!path.isFile || path.length() <= 0L) return false
        val db = SQLiteDatabase.openDatabase(
            path.path, null, SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val schema = db.rawQuery("SELECT value FROM meta WHERE key='schema'", null)
                .use { it.moveToFirst() && it.getString(0) == "1" }
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
            notifyStatus()
            latestRequest?.let(::lookup)
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
            assets.open("vietnam_road_data.db.gz").use { unpack(it, temp) }
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
                if (!closed) onUpdate(pins, trustedLimit, true, cacheSource)
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
        downloader.shutdownNow()
    }
}
