package com.taxidriver.launcher.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.renderer.widget.BitmapWidget
import com.mapbox.maps.renderer.widget.WidgetPosition

/** Debug builds only: small status overlay, not a regulatory road-sign cue. */
@OptIn(MapboxExperimental::class)
internal class VietnamDataDebugWidget : BitmapWidget(
    bitmap("CHƯA NẠP", 0),
    WidgetPosition.Builder()
        .setHorizontalAlignment(WidgetPosition.Horizontal.LEFT)
        .setVerticalAlignment(WidgetPosition.Vertical.TOP)
        .setOffsetX(24f)
        .setOffsetY(22f)
        .build(),
) {
    private var status = "CHƯA NẠP"
    private var pins = 0

    fun update(source: String, count500m: Int) {
        if (status == source && pins == count500m) return
        status = source
        pins = count500m
        updateBitmap(bitmap(source, count500m))
    }

    companion object {
        private fun bitmap(source: String, count: Int): Bitmap {
            val image = Bitmap.createBitmap(270, 92, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(213, 19, 34, 50)
            }
            canvas.drawRoundRect(RectF(0f, 0f, 270f, 92f), 16f, 16f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = 21f
            canvas.drawText("OSM VN: " + source.take(14), 13f, 37f, paint)
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textSize = 21f
            canvas.drawText("Biển trong 500m: $count", 13f, 69f, paint)
            return image
        }
    }
}
