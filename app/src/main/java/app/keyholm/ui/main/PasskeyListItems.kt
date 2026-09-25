package app.keyholm.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keyholm.store.MigrationPlaceholder
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.RecordLifecycle
import app.keyholm.ui.common.rpLabel
import app.keyholm.ui.common.userLabel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.math.abs

private val ICON_FULLY_VISIBLE_WIDTH = 72.dp
private const val BOUNCE_OVERSHOOT_SCALE = 1.3f
private const val BOUNCE_OVERSHOOT_ROTATION = 12f
private const val BOUNCE_IMPACT_MS = 80
private const val GROWTH_IMPACT_MS = 180

internal const val QUEUED_FOR_RECREATION_LABEL = "Queued for recreation"

@Composable
private fun SwipeToDeleteBackground(dismissState: SwipeToDismissBoxState) {
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
private fun PasskeyListItemSupportingContent(
    record: PasskeyRecord,
    compactView: Boolean,
    df: DateFormat,
) {
    if (compactView) {
        Text(
            userLabel(record.user.name, record.user.displayName),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Column {
            Text(userLabel(record.user.name, record.user.displayName))
            val algorithmLabel =
                "${record.keystore.coseAlgorithm.displayName}/${record.keystore.securityLevel.label}"
            Text(algorithmLabel, style = MaterialTheme.typography.bodySmall)
            Text(
                "Last used: " + df.format(Date.from(record.lastUsedAt)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (record.hasPrf) {
                Text("PRF supported", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BoxScope.PasskeyListItemTrailingIcon(
    onCancelDelete: (() -> Unit)?,
    likelyInvalid: Boolean,
    contentColor: Color?,
) {
    Box(
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (onCancelDelete != null) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                IconButton(onClick = onCancelDelete) {
                    Icon(
                        Icons.Default.Replay,
                        contentDescription = "Cancel delete",
                        tint = contentColor ?: LocalContentColor.current,
                    )
                }
            }
        } else if (likelyInvalid) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Permanently invalidated",
                tint = contentColor ?: MaterialTheme.colorScheme.error,
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PasskeyListItemContent(
    record: PasskeyRecord,
    display: PasskeyRowDisplay,
    onCancelDelete: (() -> Unit)? = null,
    contentColor: Color? = null,
) {
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val effectiveContentColor =
        contentColor
            ?: if (record.likelyInvalid) MaterialTheme.colorScheme.onErrorContainer else null
    Box(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            supportingContent = { PasskeyListItemSupportingContent(record, display.compactView, df) },
            trailingContent = { Spacer(Modifier.size(48.dp)) },
            colors =
                if (effectiveContentColor != null) {
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = effectiveContentColor,
                        supportingColor = effectiveContentColor,
                    )
                } else {
                    ListItemDefaults.colors(containerColor = Color.Transparent)
                },
        ) {
            Text(
                rpLabel(record.rp, display.preferRpName),
                maxLines = if (display.compactView) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PasskeyListItemTrailingIcon(onCancelDelete, record.likelyInvalid, effectiveContentColor)
    }
}

@Composable
private fun PendingDeleteItem(
    record: PasskeyRecord,
    pendingDeleteAt: Instant,
    display: PasskeyRowDisplay,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember(pendingDeleteAt) { Animatable(0f) }
    LaunchedEffect(pendingDeleteAt) {
        val remainingMs = Duration.between(Instant.now(), pendingDeleteAt).toMillis().coerceIn(0L, UNDO_WINDOW_MS)
        progress.snapTo(1f - remainingMs.toFloat() / UNDO_WINDOW_MS)
        progress.animateTo(1f, tween(remainingMs.toInt(), easing = LinearEasing))
    }
    val fillColor = MaterialTheme.colorScheme.error
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val baseContentColor = MaterialTheme.colorScheme.onSurface
    val fillContentColor = MaterialTheme.colorScheme.onError
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
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // repaints just the glyph pixels the fill has already passed, alpha intact
                        drawRect(
                            color = fillContentColor,
                            size = size.copy(width = size.width * progress.value.coerceIn(0f, 1f)),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
        ) {
            PasskeyListItemContent(
                record = record,
                display = display,
                onCancelDelete = onCancelDelete,
                contentColor = baseContentColor,
            )
        }
    }
}

internal typealias DeleteConfirmationRequester = (DeleteConfirmationRequest) -> Unit

internal data class PasskeyRowDisplay(
    val compactView: Boolean,
    val preferRpName: Boolean,
)

internal data class PasskeyRowActions(
    val onDelete: (PasskeyRecord) -> Unit,
    val onOpenDetails: (PasskeyRecord) -> Unit,
    val onCancelDelete: (PasskeyRecord) -> Unit,
    val requestDeleteConfirmation: DeleteConfirmationRequester,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasskeyItem(
    record: PasskeyRecord,
    display: PasskeyRowDisplay,
    actions: PasskeyRowActions,
    modifier: Modifier = Modifier,
) {
    val lifecycle = record.lifecycle
    if (lifecycle is RecordLifecycle.PendingDelete) {
        PendingDeleteItem(
            record = record,
            pendingDeleteAt = lifecycle.at,
            display = display,
            onCancelDelete = { actions.onCancelDelete(record) },
            modifier = modifier,
        )
        return
    }

    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }
    val scope = rememberCoroutineScope()

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                actions.requestDeleteConfirmation(
                    DeleteConfirmationRequest(
                        record = record,
                        onConfirmed = { actions.onDelete(record) },
                        onDenied = { scope.launch { dismissState.reset() } },
                    ),
                )
            }
        },
        backgroundContent = { SwipeToDeleteBackground(dismissState) },
    ) {
        Card(
            onClick = { actions.onOpenDetails(record) },
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (record.likelyInvalid) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                ),
        ) {
            PasskeyListItemContent(record = record, display = display)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MigrationPlaceholderItem(
    placeholder: MigrationPlaceholder,
    preferRpName: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState()

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                onDismiss()
            }
        },
        backgroundContent = { SwipeToDeleteBackground(dismissState) },
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            MigrationPlaceholderCardBody(placeholder, preferRpName)
        }
    }
}

@Composable
internal fun MigrationPlaceholderCardBody(
    placeholder: MigrationPlaceholder,
    preferRpName: Boolean,
    showStatusIcon: Boolean = true,
) {
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Box {
        ListItem(
            supportingContent = {
                Column {
                    Text(placeholder.userName)
                    Text(
                        "originally created " + df.format(Date.from(placeholder.originalCreatedAt)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            leadingContent =
                if (showStatusIcon) {
                    { Spacer(Modifier.size(24.dp)) }
                } else {
                    null
                },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) {
            Text(rpLabel(placeholder.rp, preferRpName))
        }
        if (showStatusIcon) {
            Icon(
                Icons.Default.CheckBoxOutlineBlank,
                contentDescription = "Not yet recreated on this device",
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
            )
        }
    }
}
