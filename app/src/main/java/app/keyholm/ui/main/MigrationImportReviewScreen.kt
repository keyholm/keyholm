package app.keyholm.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.keyholm.iconpack.IconPack
import app.keyholm.store.MigrationPlaceholder
import app.keyholm.ui.common.rememberAppIcon

private const val QUEUED_FOR_RECREATION_LABEL = "Queued for recreation"
private const val DUPLICATE_LABEL = "Skipped duplicates"
private const val KNOWN_ENTRY_ALPHA = 0.38f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MigrationImportReviewScreen(
    preview: ImportPreview,
    preferRpName: Boolean,
    iconPack: IconPack?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val display = PlaceholderRowDisplay(preferRpName, iconPack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import") },
                navigationIcon = {
                    Image(
                        bitmap = rememberAppIcon(),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 24.dp, end = 16.dp).size(24.dp).clip(CircleShape),
                    )
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = onConfirm, enabled = preview.fresh.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Import") }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (preview.fresh.isNotEmpty()) {
                item { ReviewSectionLabel(QUEUED_FOR_RECREATION_LABEL) }
                items(preview.fresh) { PlaceholderReviewCard(it, display) }
            }
            if (preview.duplicatePasskeys.isNotEmpty() || preview.duplicatePlaceholders.isNotEmpty()) {
                item { ReviewSectionLabel(DUPLICATE_LABEL) }
                items(preview.duplicatePlaceholders) { PlaceholderReviewCard(it, display, Modifier.alpha(KNOWN_ENTRY_ALPHA)) }
                items(preview.duplicatePasskeys) { PasskeyReviewCard(it, display, Modifier.alpha(KNOWN_ENTRY_ALPHA)) }
            }
        }
    }
}

@Composable
private fun ReviewSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun PasskeyReviewCard(
    placeholder: MigrationPlaceholder,
    display: PlaceholderRowDisplay,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        MigrationPlaceholderCardBody(placeholder, display)
    }
}

@Composable
private fun PlaceholderReviewCard(
    placeholder: MigrationPlaceholder,
    display: PlaceholderRowDisplay,
    modifier: Modifier = Modifier,
) {
    val borderColor = MaterialTheme.colorScheme.tertiary
    Card(
        modifier = modifier.fillMaxWidth().placeholderBorder(borderColor),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        MigrationPlaceholderCardBody(placeholder, display)
    }
}
