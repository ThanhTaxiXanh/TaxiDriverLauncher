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
 * Screen-space speed HUD. Unlike a map style SymbolLayer it stays fixed in the
 * lower-left corner through panning, zooming, rotation and 3D perspective.
 */
@OptIn(MapboxExperimental::class)
internal class DrivingSpeedWidget(
    night: Boolean,
) : BitmapWidget(
    renderBitmap(null, night),
    WidgetPosition.Builder()
        .setHorizontalAlignment(WidgetPosition.Horizontal.LEFT)
        .setVerticalAlignment(WidgetPosition.Vertical.BOTTOM)
        .setOffsetX(28f)
        .setOffsetY(-68f)
        .build(),
) {
    private var speed: Int? = null
    private var nightMode = night
    private var overLimit = false

    fun update(value: Int?, night: Boolean, overLimit: Boolean = false) {
        if (speed == value && nightMode == night && this.overLimit == overLimit) return
        speed = value
        nightMode = night
        this.overLimit = overLimit
        updateBitmap(renderBitmap(value, night, overLimit))
    }

    companion object {
        private fun renderBitmap(speed: Int?, night: Boolean, overLimit: Boolean = false): Bitmap {
            val bitmap = Bitmap.createBitmap(240, 142, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            paint.color = if (night) Color.argb(235, 18, 29, 43) else Color.argb(234, 252, 252, 252)
            canvas.drawRoundRect(RectF(5f, 5f, 235f, 137f), 28f, 28f, paint)

            paint.color = if (overLimit) Color.rgb(225, 51, 51) else if (night) Color.WHITE else Color.rgb(22, 36, 53)
            paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            paint.textSize = 68f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(speed?.toString() ?: "—", 120f, 83f, paint)

            paint.color = if (night) Color.rgb(177, 203, 227) else Color.rgb(75, 91, 113)
            paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            paint.textSize = 26f
            canvas.drawText("km/h", 120f, 117f, paint)
            return bitmap
        }
    }
}
