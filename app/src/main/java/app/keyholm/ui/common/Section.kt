package app.keyholm.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val INNER_RADIUS = 4.dp

@Composable
internal fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    outerRadius: Dp = 16.dp,
    content: SectionScope.() -> Unit,
) {
    val scope = SectionScope().apply(content)
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(outerRadius),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Column {
                scope.items.forEachIndexed { index, item ->
                    val attachedBelow = scope.items.getOrNull(index + 1)?.attachedToPrevious == true
                    val topRadius =
                        when {
                            index == 0 -> outerRadius
                            item.attachedToPrevious -> 0.dp
                            else -> INNER_RADIUS
                        }
                    val bottomRadius =
                        when {
                            index == scope.items.lastIndex -> outerRadius
                            attachedBelow -> 0.dp
                            else -> INNER_RADIUS
                        }
                    item.content(
                        RoundedCornerShape(
                            topStart = topRadius,
                            topEnd = topRadius,
                            bottomStart = bottomRadius,
                            bottomEnd = bottomRadius,
                        ),
                    )
                    if (index != scope.items.lastIndex && !attachedBelow) {
                        HorizontalDivider(thickness = 2.dp, color = Color.Transparent)
                    }
                }
            }
        }
    }
}
