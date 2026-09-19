package app.keyholm.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val CONTENT_DESCRIPTION_BACK = "Back"

@Composable
internal fun BackButton(onBack: () -> Unit) {
    FilledIconButton(
        onClick = onBack,
        modifier = Modifier.padding(start = 16.dp, end = 8.dp),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = CONTENT_DESCRIPTION_BACK)
    }
}
