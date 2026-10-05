package app.keyholm.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.keyholm.R

internal val SheetHeadlineTrim =
    LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.LastLineBottom,
    )
internal val SheetSupportingTrim =
    LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.FirstLineTop,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surfaceBright,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        content = content,
    )
}

@Composable
internal fun SheetHeader(
    icon: ImageBitmap,
    subtitle: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(CircleShape),
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

@Composable
internal fun SheetSection(content: SectionScope.() -> Unit) {
    Section(
        title = null,
        outerRadius = 28.dp,
        modifier = Modifier.padding(horizontal = 8.dp),
        content = content,
    )
}

@Composable
internal fun SheetRow(
    title: String,
    supporting: String?,
    icon: ImageBitmap,
    shape: Shape,
    onClick: () -> Unit,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 60.dp),
        supportingContent =
            supporting?.let {
                {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = SheetSupportingTrim),
                    )
                }
            },
        leadingContent = {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp).clip(CircleShape),
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeightStyle = SheetHeadlineTrim),
            fontWeight = FontWeight.Medium,
        )
    }
}
