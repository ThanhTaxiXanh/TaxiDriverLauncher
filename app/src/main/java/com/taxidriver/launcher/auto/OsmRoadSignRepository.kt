package com.taxidriver.launcher.auto

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Small, rate-limited Overpass client. Public infrastructure is best-effort;
 * no retries in a tight loop, no queries on every GPS update.
 * No sign is shown if the service, OSM tags or road affiliation are ambiguous.
 */
internal class OsmRoadSignRepository(
    private val onUpdate: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private var request: Call? = null
    private var center: RoadCoordinate? = null
    private var signs: List<RoadSign> = emptyList()
    private var lastRequestMs = -1_000_000L
    private var lastSuccessMs = -1_000_000L
    private var closed = false

    fun nearby(position: RoadCoordinate): List<RoadSign> {
        val c = center ?: return emptyList()
        return if (SystemClock.elapsedRealtime() - lastSuccessMs < 600_000L &&
            distanceMeters(c, position) <= 360.0
        ) signs else emptyList()
    }

    fun maybeFetch(position: RoadCoordinate) {
        if (closed || request != null ||
            !position.latitude.isFinite() || !position.longitude.isFinite() ||
            position.latitude !in -90.0..90.0 || position.longitude !in -180.0..180.0
        ) return
        val now = SystemClock.elapsedRealtime()
        // No more than one network query per minute, even after a failed call.
        if (now - lastRequestMs < 60_000L) return
        if (nearby(position).isNotEmpty() && center?.let {
                distanceMeters(it, position) < 200.0
            } == true
        ) return
        // Cache an empty successful result, too: absence of OSM signs is normal.
        if (now - lastSuccessMs < 480_000L &&
            center?.let { distanceMeters(it, position) < 200.0 } == true
        ) return

        lastRequestMs = now
        val query = buildQuery(position)
        val body = FormBody.Builder().add("data", query).build()
        val req = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .header("User-Agent", "TaxiDriverLauncher/3.0.3 (+https://github.com/ThanhTaxiXanh/TaxiDriverLauncher)")
            .post(body)
            .build()
        val call = client.newCall(req)
        request = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    if (request === call) request = null
                    // Network failures are normal; do not show stale sign warnings.
                    Log.d("TaxiDriverAuto", "OSM signs temporarily unavailable")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val parsed = try {
                    response.use { res ->
                        if (!res.isSuccessful) null
                        else res.body?.charStream()?.use { reader ->
                            parse(JSONObject(reader.readText()))
                        }
                    }
                } catch (e: Exception) {
                    Log.w("TaxiDriverAuto", "OSM sign data ignored", e)
                    null
                }
                mainHandler.post {
                    if (closed || request !== call) return@post
                    request = null
                    if (parsed != null) {
                        center = position
                        signs = parsed
                        lastSuccessMs = SystemClock.elapsedRealtime()
                        onUpdate()
                    }
                }
            }
        })
    }

    fun close() {
        closed = true
        request?.cancel()
        request = null
        signs = emptyList()
    }

    private fun buildQuery(position: RoadCoordinate): String {
        val at = "550," + position.latitude + "," + position.longitude
        // Only nodes explicitly mapped as traffic signs/road hazards.
        // Include parent highway ways to associate signs with a road and its
        // geometry; separate signs without verifiable way membership are ignored.
        return "[out:json][timeout:12];(" +
            "node(around:" + at + ")[traffic_sign];" +
            "node(around:" + at + ")[highway~\\\"^(stop|give_way)$\\\"];" +
            "node(around:" + at + ")[railway=level_crossing];" +
            ")->.signs;(.signs;way(bn.signs)[highway];);out body geom;"
    }

    private fun parse(json: JSONObject): List<RoadSign> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        val nodes = mutableMapOf<Long, Pair<RoadCoordinate, Pair<RoadSignKind, String>>>()
        for (i in 0 until elements.length().coerceAtMost(1800)) {
            val item = elements.optJSONObject(i) ?: continue
            if (item.optString("type") != "node") continue
            val tags = item.optJSONObject("tags") ?: continue
            val kind = identify(tags) ?: continue
            val direction = tags.optString("traffic_sign:direction")
                .ifBlank { tags.optString("direction") }
            // Relative orientation only. Compass bearings on freestanding signs
            // use different OSM semantics and are deliberately excluded.
            if (direction != "forward" && direction != "backward") continue
            nodes[item.optLong("id")] = RoadCoordinate(
                item.optDouble("lat"), item.optDouble("lon"),
            ) to (kind to direction)
        }
        if (nodes.isEmpty()) return emptyList()

        val results = mutableListOf<RoadSign>()
        for (i in 0 until elements.length().coerceAtMost(1800)) {
            val item = elements.optJSONObject(i) ?: continue
            if (item.optString("type") != "way") continue
            val tags = item.optJSONObject("tags") ?: continue
            val roadName = tags.optString("name:vi").ifBlank { tags.optString("name") }
            if (roadName.isBlank()) continue
            val ids = item.optJSONArray("nodes") ?: continue
            val coords = item.optJSONArray("geometry") ?: continue
            if (ids.length() != coords.length() || ids.length() > 900) continue
            val nodeIds = (0 until ids.length()).map { ids.optLong(it) }
            val geometry = (0 until coords.length()).mapNotNull { index ->
                val p = coords.optJSONObject(index) ?: return@mapNotNull null
                RoadCoordinate(p.optDouble("lat"), p.optDouble("lon"))
            }
            if (geometry.size != nodeIds.size) continue
            for (nodeId in nodeIds) {
                val candidate = nodes[nodeId] ?: continue
                results.add(
                    RoadSign(nodeId, candidate.second.first, candidate.first,
                        roadName, geometry, nodeIds, candidate.second.second),
                )
                if (results.size >= 120) return results
            }
        }
        return results
    }

    private fun identify(tags: JSONObject): RoadSignKind? {
        return when {
            tags.optString("highway") == "stop" -> RoadSignKind.STOP
            tags.optString("highway") == "give_way" -> RoadSignKind.GIVE_WAY
            tags.optString("railway") == "level_crossing" -> RoadSignKind.RAILWAY
            else -> when (tags.optString("traffic_sign").lowercase()) {
                "stop" -> RoadSignKind.STOP
                "give_way", "yield" -> RoadSignKind.GIVE_WAY
                "pedestrian_crossing" -> RoadSignKind.PEDESTRIAN
                "hazard", "danger" -> RoadSignKind.HAZARD
                else -> null
            }
        }
    }

    private fun distanceMeters(a: RoadCoordinate, b: RoadCoordinate): Double {
        val lat = (a.latitude + b.latitude) / 2.0
        val east = (a.longitude - b.longitude) * 111_320.0 *
            cos(Math.toRadians(lat))
        val north = (a.latitude - b.latitude) * 111_320.0
        return hypot(east, north)
    }
}
