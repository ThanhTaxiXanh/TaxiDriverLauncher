package com.taxidriver.launcher.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.renderer.widget.BitmapWidget
import com.mapbox.maps.renderer.widget.WidgetPosition

/** Only shows a real, nonstale map-matched speed limit; otherwise shows a dash. */
@OptIn(MapboxExperimental::class)
internal class SpeedLimitWidget : BitmapWidget(
    renderBitmap(null, false),
    WidgetPosition.Builder()
        .setHorizontalAlignment(WidgetPosition.Horizontal.RIGHT)
        .setVerticalAlignment(WidgetPosition.Vertical.BOTTOM)
        .setOffsetX(-28f)
        .setOffsetY(-68f)
        .build(),
) {
    private var currentLimit: Int? = null
    private var warning = false

    fun update(limitKmh: Int?, overLimit: Boolean) {
        if (currentLimit == limitKmh && warning == overLimit) return
        currentLimit = limitKmh
        warning = overLimit
        updateBitmap(renderBitmap(limitKmh, overLimit))
    }

    companion object {
        private fun renderBitmap(limitKmh: Int?, overLimit: Boolean): Bitmap {
            val bitmap = Bitmap.createBitmap(142, 142, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE
            canvas.drawCircle(71f, 71f, 62f, paint)
            paint.color = if (overLimit) Color.rgb(176, 23, 32) else Color.rgb(211, 39, 45)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 14f
            canvas.drawCircle(71f, 71f, 56f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(25, 25, 25)
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = 55f
            canvas.drawText(limitKmh?.toString() ?: "—", 71f, 90f, paint)
            return bitmap
        }
    }
}
