package com.taxidriver.launcher.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.renderer.widget.BitmapWidget
import com.mapbox.maps.renderer.widget.WidgetPosition

/**
 * Non-regulatory informational HUD for up to two OSM signs beyond the
 * current zoom-18 map viewport. Distances are straight-line from GPS.
 */
@OptIn(MapboxExperimental::class)
internal class OffscreenRoadSignWidget(night: Boolean) : BitmapWidget(
    draw(emptyList(), night),
    WidgetPosition.Builder()
        .setHorizontalAlignment(WidgetPosition.Horizontal.RIGHT)
        .setVerticalAlignment(WidgetPosition.Vertical.TOP)
        .setOffsetX(-18f)
        .setOffsetY(18f)
        .build(),
) {
    private var shown: List<OffscreenRoadSign> = emptyList()
    private var nightMode: Boolean = night

    fun update(signs: List<OffscreenRoadSign>, night: Boolean) {
        val rounded = signs.map { it.copy(distanceMeters = ((it.distanceMeters + 5) / 10) * 10) }
        if (shown == rounded && nightMode == night) return
        shown = rounded
        nightMode = night
        updateBitmap(draw(rounded, night))
    }

    companion object {
        private fun draw(signs: List<OffscreenRoadSign>, night: Boolean): Bitmap {
            val bmp = Bitmap.createBitmap(315, 158, Bitmap.Config.ARGB_8888)
            if (signs.isEmpty()) return bmp
            val canvas = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = if (night) Color.argb(231, 18, 31, 49)
                else Color.argb(239, 254, 254, 254)
            canvas.drawRoundRect(RectF(2f, 2f, 312f, 151f), 19f, 19f, p)
            p.color = if (night) Color.LTGRAY else Color.rgb(43, 58, 72)
            p.textSize = 18f
            p.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText("BIỂN OSM GẦN XE • THAM KHẢO", 12f, 25f, p)
            for ((index, sign) in signs.take(2).withIndex()) {
                val y = 70f + index * 52f
                p.color = Color.rgb(209, 42, 51)
                canvas.drawCircle(28f, y - 9f, 19f, p)
                p.color = Color.WHITE
                p.textAlign = Paint.Align.CENTER
                p.textSize = 22f
                canvas.drawText("i", 28f, y - 2f, p)
                p.textAlign = Paint.Align.LEFT
                p.color = if (night) Color.WHITE else Color.rgb(25, 36, 50)
                p.typeface = android.graphics.Typeface.DEFAULT_BOLD
                p.textSize = 18f
                canvas.drawText(sign.title.take(24), 56f, y - 12f, p)
                p.typeface = android.graphics.Typeface.DEFAULT
                p.textSize = 18f
                canvas.drawText("${sign.relativeDirection} · ${sign.distanceMeters} m từ xe", 56f, y + 12f, p)
            }
            return bmp
        }
    }
}
