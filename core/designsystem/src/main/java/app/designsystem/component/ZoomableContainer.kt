package app.designsystem.component

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

@Composable
fun ZoomableContainer(
    modifier: Modifier = Modifier,
    onZoomStateChanged: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(scale) {
        onZoomStateChanged(false)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var lastTapTime = 0L
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val downChanges = event.changes.filter { it.pressed && !it.previousPressed }

                        if (downChanges.size == 1 && event.changes.size == 1) {
                            val currentTime = downChanges.first().uptimeMillis
                            if (currentTime - lastTapTime < 400L) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                                event.changes.forEach { it.consume() }
                                lastTapTime = 0L
                            } else {
                                lastTapTime = currentTime
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)

                    if (scale > 1f) {
                        val maxX = (containerSize.width * (scale - 1)) / 2
                        val maxY = (containerSize.height * (scale - 1)) / 2

                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
        ) {
            content()
        }
    }
}