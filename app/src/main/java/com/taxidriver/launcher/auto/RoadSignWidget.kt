package com.taxidriver.launcher.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.renderer.widget.BitmapWidget
import com.mapbox.maps.renderer.widget.WidgetPosition

/** Small, non-interactive HUD for an explicitly matched OSM road sign. */
@OptIn(MapboxExperimental::class)
internal class RoadSignWidget(night: Boolean) : BitmapWidget(
    draw(null, night),
    WidgetPosition.Builder()
        .setHorizontalAlignment(WidgetPosition.Horizontal.CENTER)
        .setVerticalAlignment(WidgetPosition.Vertical.TOP)
        .setOffsetX(0f)
        .setOffsetY(62f)
        .build(),
) {
    private var current: UpcomingRoadSign? = null
    private var nightMode: Boolean = night

    fun update(sign: UpcomingRoadSign?, night: Boolean) {
        // Bucket by 10 m to avoid allocating a bitmap every GPS frame.
        val rounded = sign?.copy(distanceMeters = ((sign.distanceMeters + 5) / 10) * 10)
        if (rounded == current && nightMode == night) return
        current = rounded
        nightMode = night
        updateBitmap(draw(rounded, night))
    }

    companion object {
        private fun draw(sign: UpcomingRoadSign?, night: Boolean): Bitmap {
            val bmp = Bitmap.createBitmap(360, 116, Bitmap.Config.ARGB_8888)
            if (sign == null) return bmp // Fully transparent when unverifiable.
            val canvas = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = if (night) Color.argb(240, 18, 29, 43) else Color.argb(242, 255, 255, 255)
            canvas.drawRoundRect(RectF(3f, 3f, 357f, 113f), 20f, 20f, p)

            when (sign.kind) {
                RoadSignKind.STOP -> {
                    val octagon = Path()
                    octagon.moveTo(31f, 19f)
                    octagon.lineTo(68f, 19f)
                    octagon.lineTo(87f, 37f)
                    octagon.lineTo(87f, 76f)
                    octagon.lineTo(68f, 94f)
                    octagon.lineTo(31f, 94f)
                    octagon.lineTo(12f, 76f)
                    octagon.lineTo(12f, 37f)
                    octagon.close()
                    p.color = Color.rgb(210, 36, 44)
                    canvas.drawPath(octagon, p)
                    p.color = Color.WHITE
                    p.textSize = 22f
                    p.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    p.textAlign = Paint.Align.CENTER
                    canvas.drawText("STOP", 49f, 64f, p)
                }
                else -> {
                    p.color = Color.rgb(214, 44, 47)
                    val triangle = Path().apply {
                        moveTo(50f, 14f)
                        lineTo(95f, 94f)
                        lineTo(5f, 94f)
                        close()
                    }
                    canvas.drawPath(triangle, p)
                    p.color = Color.WHITE
                    p.textSize = 45f
                    p.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    p.textAlign = Paint.Align.CENTER
                    canvas.drawText(if (sign.kind == RoadSignKind.RAILWAY) "×" else "!", 50f, 83f, p)
                }
            }
            p.textAlign = Paint.Align.LEFT
            p.typeface = android.graphics.Typeface.DEFAULT_BOLD
            p.color = if (night) Color.WHITE else Color.rgb(25, 39, 53)
            p.textSize = 23f
            canvas.drawText(sign.kind.title, 108f, 40f, p)
            p.textSize = 31f
            canvas.drawText(sign.distanceMeters.toString() + " m", 108f, 76f, p)
            p.textSize = 14f
            p.typeface = android.graphics.Typeface.DEFAULT
            p.color = if (night) Color.LTGRAY else Color.GRAY
            canvas.drawText("Dữ liệu OSM", 108f, 99f, p)
            return bmp
        }
    }
}
