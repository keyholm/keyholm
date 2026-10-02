package app.keyholm.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keyholm.iconpack.IconPack
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.RecordLifecycle
import app.keyholm.ui.common.RP_ICON_SIZE
import app.keyholm.ui.common.RP_ICON_START
import app.keyholm.ui.common.RpIcon
import app.keyholm.ui.common.promptContent
import app.keyholm.ui.common.rpLabel
import app.keyholm.ui.common.userLabel
import kotlinx.coroutines.channels.Channel
import java.text.DateFormat
import java.time.Instant
import java.util.Date

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
            CancelDeleteButton(onCancelDelete, contentColor ?: LocalContentColor.current)
        } else if (likelyInvalid) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Permanently invalidated",
                tint = contentColor ?: MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun CancelDeleteButton(
    onClick: () -> Unit,
    tint: Color,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        IconButton(onClick = onClick) {
            Icon(Icons.Default.Replay, contentDescription = "Cancel delete", tint = tint)
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
            leadingContent = { Spacer(Modifier.size(RP_ICON_SIZE)) },
            trailingContent =
                if (onCancelDelete != null || record.likelyInvalid) {
                    { Spacer(Modifier.size(48.dp)) }
                } else {
                    null
                },
            colors =
                if (contentColor == null && record.likelyInvalid) {
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = MaterialTheme.colorScheme.onErrorContainer,
                        supportingColor = MaterialTheme.colorScheme.onErrorContainer,
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
private fun PendingPasskeyItem(
    record: PasskeyRecord,
    pendingDeleteAt: Instant,
    display: PasskeyRowDisplay,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PendingDeleteItem(
        pendingDeleteAt = pendingDeleteAt,
        baseColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        overlay = {
            RpIcon(
                display.iconPack,
                record.rp,
                display.preferRpName,
                Modifier.align(Alignment.CenterStart).padding(start = RP_ICON_START),
            )
        },
        modifier = modifier,
    ) { contentColor ->
        PasskeyListItemContent(
            record = record,
            display = display,
            onCancelDelete = onCancelDelete,
            contentColor = contentColor,
        )
    }
}

internal data class PasskeyRowDisplay(
    val compactView: Boolean,
    val preferRpName: Boolean,
    val iconPack: IconPack?,
)

internal data class PasskeyRowActions(
    val onDelete: (PasskeyRecord) -> Unit,
    val onOpenDetails: (PasskeyRecord) -> Unit,
    val onCancelDelete: (PasskeyRecord) -> Unit,
    val requestDeleteConfirmation: DeleteConfirmationRequester<PasskeyRecord>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasskeyItem(
    record: PasskeyRecord,
    display: PasskeyRowDisplay,
    actions: PasskeyRowActions,
    modifier: Modifier = Modifier,
) {
    PendingDeleteSwitch(record.lifecycle, modifier) { lifecycle ->
        if (lifecycle is RecordLifecycle.PendingDelete) {
            PendingPasskeyItem(
                record = record,
                pendingDeleteAt = lifecycle.at,
                display = display,
                onCancelDelete = { actions.onCancelDelete(record) },
            )
            return@PendingDeleteSwitch
        }

        SwipeToDeleteBox(
            onDelete = { reset -> actions.requestDeleteConfirmation(record, { actions.onDelete(record) }, reset) },
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
                Box(modifier = Modifier.fillMaxWidth()) {
                    PasskeyListItemContent(record = record, display = display)
                    RpIcon(
                        display.iconPack,
                        record.rp,
                        display.preferRpName,
                        Modifier.align(Alignment.CenterStart).padding(start = RP_ICON_START),
                    )
                }
            }
        }
    }
}

internal fun passkeyDeleteRequester(
    queue: Channel<DeleteConfirmationRequest>,
    preferRpName: Boolean,
    planFor: suspend (PasskeyRecord) -> DeletePlan,
): DeleteConfirmationRequester<PasskeyRecord> =
    { record, onConfirmed, onDenied ->
        queue.trySend(
            DeleteConfirmationRequest(
                title = "Delete passkey",
                content = promptContent("Confirm your identity to delete this passkey:", record, preferRpName),
                plan = { planFor(record) },
                onConfirmed = onConfirmed,
                onDenied = onDenied,
            ),
        )
    }
