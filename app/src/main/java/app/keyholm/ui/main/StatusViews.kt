package app.keyholm.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun WarningCard(
    message: String,
    bullets: List<String>,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column {
            ListItem(
                modifier = Modifier.padding(top = 8.dp),
                supportingContent = {
                    Column {
                        bullets.forEach { bullet ->
                            Text(
                                "•  $bullet",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null)
                },
                colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        supportingColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        leadingIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
            ) {
                Text(message, style = MaterialTheme.typography.titleSmall)
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onDismiss) {
                    Text("I understand")
                }
            }
        }
    }
}

// Puts the message at a little above center
private const val EMPTY_STATE_BOTTOM_WEIGHT = 4f

@Composable
internal fun EmptyPasskeysMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Default.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(48.dp).width(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No passkeys yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(EMPTY_STATE_BOTTOM_WEIGHT))
    }
}

internal const val LOAD_ERROR = "Failed to load data, check device storage."

@Composable
internal fun LoadErrorCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.height(48.dp).width(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            LOAD_ERROR,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.weight(EMPTY_STATE_BOTTOM_WEIGHT))
    }
}

@Composable
internal fun StatusRow(
    isOk: Boolean,
    okText: String,
    notOkText: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(text = if (isOk) okText else notOkText, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val STATUS_ALL_FEATURES_OK = "All features supported."
private const val STATUS_TEE_OK = "Hardware-backed (TEE) keystore found."
private const val STATUS_TEE_NOT_OK = "No hardware-backed (TEE) keystore found on this device."
private const val STATUS_TEE_ML_DSA_OK = "TEE supports ML-DSA."
private const val STATUS_TEE_ML_DSA_NOT_OK = "TEE doesn't support ML-DSA."
private const val STATUS_SECURE_ELEMENT_OK = "Dedicated secure element (StrongBox) found."
private const val STATUS_SECURE_ELEMENT_NOT_OK =
    "No dedicated secure element (StrongBox) found. This app is not supported."
private const val STATUS_PROVIDER_OK = "Keyholm is enabled as a passkey service."
private const val STATUS_PROVIDER_NOT_OK = "Keyholm is not enabled as a passkey service."

@Composable
internal fun DeviceStatusRows(device: DeviceStatus) {
    if (device.tee && device.teeMlDsa && device.secureElement) {
        StatusRow(
            isOk = true,
            okText = STATUS_ALL_FEATURES_OK,
            notOkText = STATUS_ALL_FEATURES_OK,
        )
    } else {
        StatusRow(
            isOk = device.tee,
            okText = STATUS_TEE_OK,
            notOkText = STATUS_TEE_NOT_OK,
        )
        if (device.tee) {
            Spacer(Modifier.height(8.dp))
            StatusRow(
                isOk = device.teeMlDsa,
                okText = STATUS_TEE_ML_DSA_OK,
                notOkText = STATUS_TEE_ML_DSA_NOT_OK,
            )
        }
        Spacer(Modifier.height(8.dp))
        StatusRow(
            isOk = device.secureElement,
            okText = STATUS_SECURE_ELEMENT_OK,
            notOkText = STATUS_SECURE_ELEMENT_NOT_OK,
        )
    }
    Spacer(Modifier.height(8.dp))
    StatusRow(
        isOk = device.providerEnabled,
        okText = STATUS_PROVIDER_OK,
        notOkText = STATUS_PROVIDER_NOT_OK,
    )
}

@Composable
internal fun StatusCard(
    device: DeviceStatus,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            Modifier.padding(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = if (device.secureElement) 8.dp else 16.dp,
            ),
        ) {
            DeviceStatusRows(device)
            if (device.secureElement) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenSettings) { Text("Open Settings to enable") }
            }
        }
    }
}
