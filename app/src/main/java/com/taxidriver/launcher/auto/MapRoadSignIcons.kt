package com.taxidriver.launcher.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale

/** Compact map pins for explicitly tagged OSM sign categories, not inferred laws. */
internal object MapRoadSignIcons {
    fun imageId(kind: RoadSignKind): String =
        "taxi-osm-sign-" + kind.name.lowercase(Locale.ROOT)

    fun create(kind: RoadSignKind): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 108, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(75, 0, 0, 0)
        canvas.drawOval(RectF(21f, 91f, 75f, 104f), paint)
        // White backing helps visibility over Mapbox dark/light and 3D.
        paint.color = Color.WHITE
        canvas.drawCircle(48f, 45f, 40f, paint)
        when (kind) {
            RoadSignKind.STOP -> {
                val shape = Path()
                for (i in 0 until 8) {
                    val angle = Math.toRadians(22.5 + i * 45.0)
                    val x = (48.0 + 36.0 * kotlin.math.cos(angle)).toFloat()
                    val y = (45.0 + 36.0 * kotlin.math.sin(angle)).toFloat()
                    if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
                }
                shape.close()
                paint.color = Color.rgb(201, 33, 42)
                canvas.drawPath(shape, paint)
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawPath(shape, paint)
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 22f
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                canvas.drawText("STOP", 48f, 52f, paint)
            }
            RoadSignKind.GIVE_WAY -> {
                val triangle = Path().apply {
                    moveTo(13f, 25f)
                    lineTo(83f, 25f)
                    lineTo(48f, 87f)
                    close()
                }
                paint.color = Color.rgb(207, 37, 43)
                canvas.drawPath(triangle, paint)
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 10f
                canvas.drawPath(triangle, paint)
                paint.style = Paint.Style.FILL
            }
            RoadSignKind.RAILWAY, RoadSignKind.PEDESTRIAN, RoadSignKind.HAZARD -> {
                paint.color = Color.rgb(204, 37, 43)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 8f
                canvas.drawCircle(48f, 45f, 36f, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(25, 40, 61)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 38f
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                val symbol = when (kind) {
                    RoadSignKind.RAILWAY -> "X"
                    RoadSignKind.PEDESTRIAN -> "P"
                    else -> "!"
                }
                canvas.drawText(symbol, 48f, 57f, paint)
            }
        }
        return bitmap
    }
}
