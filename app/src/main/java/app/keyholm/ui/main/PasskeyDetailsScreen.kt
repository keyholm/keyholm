package app.keyholm.ui.main

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.security.keystore.KeyProperties
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.store.PasskeyRecord
import app.keyholm.ui.common.BackButton
import app.keyholm.ui.common.Section
import app.keyholm.ui.common.rpDisplayName
import app.keyholm.ui.common.rpLabel
import app.keyholm.ui.common.userLabel
import app.keyholm.util.sha256
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.RelyingParty
import com.google.protobuf.ByteString
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val TOAST_COPIED = "Copied to clipboard"

@Composable
private fun DetailRow(
    label: String,
    value: String,
    shape: Shape,
) {
    val context = LocalContext.current
    ListItem(
        selected = false,
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip =
                ClipData.newPlainText(label, value).apply {
                    description.extras =
                        PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                }
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, TOAST_COPIED, Toast.LENGTH_SHORT).show()
        },
        supportingContent = { Text(value, style = MaterialTheme.typography.bodySmall) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(label)
    }
}

@Composable
private fun LikelyInvalidWarning() {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        ListItem(
            leadingContent = { Icon(Icons.Default.Warning, contentDescription = null) },
            colors =
                ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = MaterialTheme.colorScheme.onErrorContainer,
                    leadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Text(
                "Permanently invalidated! Did you change your biometrics enrollment or disable the lock screen? " +
                    "This key needs to be recreated!",
            )
        }
    }
}

@Composable
private fun AttestationRow(
    onViewAttestation: () -> Unit,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = onViewAttestation,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text("Attestation")
    }
}

private fun credentialKeyFingerprint(spki: ByteString): String =
    sha256(spki.toByteArray()).joinToString(":") {
        "%02X".format(Locale.ROOT, it)
    }

@Composable
private fun DetailsSection(
    record: PasskeyRecord,
    authenticators: Loadable<KeyAuthenticators>,
    preferRpName: Boolean,
    onViewAttestation: () -> Unit,
) {
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val algorithmName = record.keystore.coseAlgorithm.displayName
    val locationLabel = record.keystore.securityLevel.label
    Section(title = "Details") {
        item { shape -> DetailRow("Relying party", rpLabel(record.rp, preferRpName), shape) }
        item { shape -> DetailRow("User", userLabel(record.user.name, record.user.displayName), shape) }
        item { shape -> DetailRow("Algorithm", algorithmName, shape) }
        item { shape -> DetailRow("Location", locationLabel, shape) }
        when (authenticators) {
            is Loadable.Loaded -> {
                item { shape -> DetailRow("Authenticators", authenticators.value.mainKey.label, shape) }
            }

            Loadable.Failed -> {
                item { shape -> DetailRow("Authenticators", LOAD_ERROR, shape) }
            }

            Loadable.Loading -> {}
        }
        item { shape -> DetailRow("Last used", df.format(Date.from(record.lastUsedAt)), shape) }
        item { shape -> DetailRow("Created", df.format(Date.from(record.createdAt)), shape) }
        item { shape -> DetailRow("Sign count", record.signCount.toString(), shape) }
        item { shape -> DetailRow("Requesting app", record.callingPackage.value.ifBlank { "(unknown)" }, shape) }
        item { shape -> DetailRow("Credential ID", record.credentialId.b64, shape) }
        item { shape ->
            DetailRow("Public key fingerprint", credentialKeyFingerprint(record.keystore.publicKeySpki), shape)
        }
        item { shape -> DetailRow("User handle", record.user.handle.b64, shape) }
        item { shape -> AttestationRow(onViewAttestation, shape) }
    }
}

@Composable
private fun PrfSection(
    securityLevel: KeySecurityLevel,
    authenticators: Loadable<KeyAuthenticators>,
) {
    Section(title = "PRF") {
        item { shape -> DetailRow("Location", securityLevel.label, shape) }
        item { shape -> DetailRow("Algorithm", KeyProperties.KEY_ALGORITHM_HMAC_SHA256, shape) }
        when (authenticators) {
            is Loadable.Loaded -> {
                val loaded = authenticators.value
                if (loaded is KeyAuthenticators.WithPrf) {
                    item { shape -> DetailRow("Authenticators", loaded.prfKey.label, shape) }
                }
            }

            Loadable.Failed -> {
                item { shape -> DetailRow("Authenticators", LOAD_ERROR, shape) }
            }

            Loadable.Loading -> {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasskeyDetailsTopBar(
    rp: RelyingParty,
    preferRpName: Boolean,
    onBack: () -> Unit,
) {
    val title = (if (preferRpName) rpDisplayName(rp) else null) ?: rp.id.value
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            BackButton(onBack)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasskeyDetailsScreen(
    credentialId: CredentialId,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = state as? MainUiState.Ready ?: return
    val passkeys = uiState.passkeys
    val record =
        if (passkeys is Stored.Available) {
            passkeys.value.find { it.credentialId == credentialId }
        } else {
            null
        }
    if (record == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val details = uiState.details
    val open = if (details is PasskeyDetails.Open && details.credentialId == record.credentialId) details else null
    val authenticators = open?.authenticators ?: Loadable.Loading
    var showAttestationDialog by remember { mutableStateOf(false) }

    LaunchedEffect(record.credentialId) {
        viewModel.loadPasskeyInfo(record)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.clearPasskeyInfo() }
    }

    val preferRpName = uiState.settings.preferRpName
    Scaffold(topBar = { PasskeyDetailsTopBar(record.rp, preferRpName, onBack) }) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding),
        ) {
            if (record.likelyInvalid) {
                LikelyInvalidWarning()
            }
            DetailsSection(
                record,
                authenticators = authenticators,
                preferRpName = preferRpName,
                onViewAttestation = { showAttestationDialog = true },
            )
            record.keystore.prfSecurityLevel?.let { PrfSection(it, authenticators) }
        }
    }

    if (showAttestationDialog) {
        AttestationDialog(
            attestation = open?.attestation ?: Loadable.Loading,
            securityLevel = record.keystore.securityLevel,
            fileName = "keyholm-attestation-${record.rp.id.value}.pem",
            onDismiss = { showAttestationDialog = false },
        )
    }
}
