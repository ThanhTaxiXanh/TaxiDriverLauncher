package io.github.seijikohara.femto.testfixtures

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.LocalHazeBlurStyle

// The glass style never writes noiseFactor, so this composition-local write is
// the one that wins over the Haze default.
private val NoiseFreeHazeBlurStyle = HazeBlurStyle { noiseFactor(0f) }

/**
 * Render Haze glass under Robolectric's native graphics. With noise on and a
 * single colour tint, Haze 2 on API 33+ folds noise and tint into one
 * RuntimeShader whose `layout(color)` uniform Robolectric's native runtime
 * rejects ("'color' is not a valid layout qualifier"), which aborts the draw.
 * Zeroing the noise keeps Haze on its separate blur → tint chain, the path Haze's
 * own Robolectric screenshot tests take. Devices are unaffected: Android 13 added
 * `layout(color)` color uniforms (`RuntimeShader.setColorUniform`).
 */
@Composable
internal fun RobolectricHaze(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHazeBlurStyle provides NoiseFreeHazeBlurStyle, content = content)
}
