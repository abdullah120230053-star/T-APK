package com.dama.tahany.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dama.tahany.protocol.TOUCH_SWIPE
import com.dama.tahany.protocol.TOUCH_TAP

@Composable
internal fun ScreenCastSurface(
    width: Int,
    height: Int,
    onSurfaceChanged: (Surface?) -> Unit,
    onTouch: (Float, Float, Float, Float, Int) -> Unit,
) {
    val surfaceChanged by rememberUpdatedState(onSurfaceChanged)
    val dispatchTouch by rememberUpdatedState(onTouch)
    val aspect = width.toFloat() / height
    val touchSlop = LocalViewConfiguration.current.touchSlop
    AndroidView(
        modifier = Modifier.widthIn(max = (460 * aspect).dp).fillMaxWidth().aspectRatio(aspect)
            .pointerInput(touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val start = down.position
                    var end: androidx.compose.ui.geometry.Offset
                    do {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            ?: return@awaitEachGesture
                        end = change.position
                        change.consume()
                    } while (change.pressed)
                    if (size.width <= 0 || size.height <= 0) return@awaitEachGesture
                    dispatchTouch(
                        (start.x / size.width).coerceIn(0f, 1f),
                        (start.y / size.height).coerceIn(0f, 1f),
                        (end.x / size.width).coerceIn(0f, 1f),
                        (end.y / size.height).coerceIn(0f, 1f),
                        if ((end - start).getDistance() < touchSlop) TOUCH_TAP else TOUCH_SWIPE,
                    )
                }
            },
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = surfaceChanged(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = surfaceChanged(null)
                })
            }
        },
    )
}
