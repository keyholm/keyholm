package app.keyholm.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.keyholm.iconpack.IconPack
import app.keyholm.store.MigrationPlaceholder
import app.keyholm.ui.common.RP_ICON_SIZE
import app.keyholm.ui.common.RP_ICON_START
import app.keyholm.ui.common.RpIcon
import app.keyholm.ui.common.rpLabel
import java.text.DateFormat
import java.util.Date

internal const val QUEUED_FOR_RECREATION_LABEL = "Queued for recreation"

private val PLACEHOLDER_BORDER_WIDTH = 2.dp
private val PLACEHOLDER_DASH = 10.dp
private val PLACEHOLDER_DASH_GAP = 7.dp
private val PLACEHOLDER_CORNER_RADIUS = 12.dp

internal data class PlaceholderRowDisplay(
    val preferRpName: Boolean,
    val iconPack: IconPack?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MigrationPlaceholderItem(
    placeholder: MigrationPlaceholder,
    display: PlaceholderRowDisplay,
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
        val borderColor = MaterialTheme.colorScheme.tertiary
        Card(
            onClick = onClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val width = PLACEHOLDER_BORDER_WIDTH.toPx()
                        drawRoundRect(
                            color = borderColor,
                            topLeft = Offset(width / 2, width / 2),
                            size = Size(size.width - width, size.height - width),
                            cornerRadius = CornerRadius(PLACEHOLDER_CORNER_RADIUS.toPx()),
                            style =
                                Stroke(
                                    width,
                                    pathEffect =
                                        PathEffect.dashPathEffect(
                                            floatArrayOf(PLACEHOLDER_DASH.toPx(), PLACEHOLDER_DASH_GAP.toPx()),
                                        ),
                                ),
                        )
                    },
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            MigrationPlaceholderCardBody(placeholder, display)
        }
    }
}

@Composable
internal fun MigrationPlaceholderCardBody(
    placeholder: MigrationPlaceholder,
    display: PlaceholderRowDisplay,
) {
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Box(modifier = Modifier.fillMaxWidth()) {
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
            leadingContent = { Spacer(Modifier.size(RP_ICON_SIZE)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) {
            Text(rpLabel(placeholder.rp, display.preferRpName))
        }
        RpIcon(
            display.iconPack,
            placeholder.rp,
            display.preferRpName,
            Modifier.align(Alignment.CenterStart).padding(start = RP_ICON_START),
        )
    }
}
