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
    bitmap("CHƯA NẠP", 0, 0, "CHƯA CÓ DỮ LIỆU"),
    WidgetPosition.Builder()
        .setHorizontalAlignment(WidgetPosition.Horizontal.LEFT)
        .setVerticalAlignment(WidgetPosition.Vertical.TOP)
        .setOffsetX(24f)
        .setOffsetY(22f)
        .build(),
) {
    private var status = "CHƯA NẠP"
    private var pins = 0
    private var inScreen = 0
    private var reason = "CHƯA CÓ DỮ LIỆU"

    fun update(source: String, count500m: Int, visible: Int, speedReason: String) {
        if (status == source && pins == count500m &&
            inScreen == visible && reason == speedReason) return
        status = source
        pins = count500m
        inScreen = visible
        reason = speedReason
        updateBitmap(bitmap(source, count500m, visible, speedReason))
    }

    companion object {
        private fun bitmap(source: String, count: Int, visible: Int, speedReason: String): Bitmap {
            val image = Bitmap.createBitmap(392, 136, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(213, 19, 34, 50)
            }
            canvas.drawRoundRect(RectF(0f, 0f, 392f, 136f), 16f, 16f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = 21f
            canvas.drawText("OSM VN: " + source.take(14), 13f, 37f, paint)
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textSize = 21f
            canvas.drawText("Biển 500m: $count · trong màn: $visible", 13f, 69f, paint)
            paint.textSize = 17f
            canvas.drawText("Giới hạn: " + speedReason.take(33), 13f, 107f, paint)
            return image
        }
    }
}
