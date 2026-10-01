package app.keyholm.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

private val ICON_FULLY_VISIBLE_WIDTH = 72.dp
private val MIN_DELETE_SWIPE = 120.dp
private const val BOUNCE_OVERSHOOT_SCALE = 1.3f
private const val BOUNCE_OVERSHOOT_ROTATION = 12f
private const val BOUNCE_IMPACT_MS = 80
private const val GROWTH_IMPACT_MS = 180

@Composable
internal fun SwipeToDeleteBox(
    onDelete: (reset: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val minSwipePx = with(LocalDensity.current) { MIN_DELETE_SWIPE.toPx() }
    val dismissState = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled) { minSwipePx } }
    val scope = rememberCoroutineScope()
    val reset: () -> Unit = { scope.launch { dismissState.reset() } }
    // A new onDismiss identity makes SwipeToDismissBox re-fire onDismiss so it needs to be stable
    val currentOnDelete by rememberUpdatedState(onDelete)

    SwipeToDismissBox(
        state = dismissState,
        modifier =
            modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    // consuming the up cancels the fling, so a short flick snaps back
                    val offset = dismissState.requireOffset()
                    if (offset != 0f && abs(offset) < minSwipePx) event.changes.forEach { it.consume() }
                }
            },
        enableDismissFromStartToEnd = false,
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) currentOnDelete(reset)
        },
        backgroundContent = { SwipeToDeleteBackground(dismissState) },
        content = content,
    )
}

@Composable
internal fun SwipeToDeleteBackground(dismissState: SwipeToDismissBoxState) {
    SwipeActionBackground(
        dismissState = dismissState,
        arrangement = Arrangement.End,
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        icon = Icons.Default.Delete,
    )
}

@Composable
internal fun SwipeActionBackground(
    dismissState: SwipeToDismissBoxState,
    arrangement: Arrangement.Horizontal,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
) {
    val revealedWidth =
        with(LocalDensity.current) {
            val offsetPx = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
            abs(offsetPx).toDp()
        }
    val isRevealed = revealedWidth > ICON_FULLY_VISIBLE_WIDTH

    val iconScale = remember { Animatable(1f) }
    val iconRotation = remember { Animatable(0f) }
    LaunchedEffect(isRevealed) {
        if (isRevealed) {
            launch {
                iconScale.animateTo(BOUNCE_OVERSHOOT_SCALE, tween(GROWTH_IMPACT_MS))
                iconScale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
            }
            iconRotation.animateTo(-BOUNCE_OVERSHOOT_ROTATION, tween(BOUNCE_IMPACT_MS))
            iconRotation.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessVeryLow))
        } else {
            iconScale.snapTo(1f)
            iconRotation.snapTo(0f)
        }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = arrangement,
    ) {
        Box(
            modifier =
                Modifier
                    .width(revealedWidth)
                    .fillMaxHeight()
                    .clip(CardDefaults.shape)
                    .background(containerColor)
                    .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = contentColor,
                modifier =
                    Modifier.graphicsLayer {
                        scaleX = iconScale.value
                        scaleY = iconScale.value
                        rotationZ = iconRotation.value
                    },
            )
        }
    }
}

@Composable
private fun rememberDeleteProgress(pendingDeleteAt: Instant): Animatable<Float, AnimationVector1D> {
    val progress = remember(pendingDeleteAt) { Animatable(0f) }
    LaunchedEffect(pendingDeleteAt) {
        val remainingMs = Duration.between(Instant.now(), pendingDeleteAt).toMillis().coerceIn(0L, UNDO_WINDOW_MS)
        progress.snapTo(1f - remainingMs.toFloat() / UNDO_WINDOW_MS)
        progress.animateTo(1f, tween(remainingMs.toInt(), easing = LinearEasing))
    }
    return progress
}

private fun Modifier.tintSweptContent(
    color: Color,
    progress: () -> Float,
) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            color = color,
            size = size.copy(width = size.width * progress().coerceIn(0f, 1f)),
            blendMode = BlendMode.SrcAtop,
        )
    }

@Composable
internal fun PendingDeleteItem(
    pendingDeleteAt: Instant,
    baseColor: Color,
    overlay: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val progress = rememberDeleteProgress(pendingDeleteAt)
    val fillColor = MaterialTheme.colorScheme.error
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(CardDefaults.shape)
                .drawBehind {
                    drawRect(color = baseColor)
                    drawRect(color = fillColor, size = size.copy(width = size.width * progress.value.coerceIn(0f, 1f)))
                },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .tintSweptContent(MaterialTheme.colorScheme.onError) { progress.value },
            ) {
                content(MaterialTheme.colorScheme.onSurface)
            }
            overlay()
        }
    }
}
