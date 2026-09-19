package app.keyholm.ui.main

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.keyholm.keystore.KeySecurityLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun LinkText(
    prefix: String,
    link: String,
    url: String,
    modifier: Modifier = Modifier,
    suffix: String = "",
) {
    Text(
        buildAnnotatedString {
            append(prefix)
            withLink(LinkAnnotation.Url(url)) { append(link) }
            append(suffix)
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

@Composable
private fun AttestationBullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun AttestationDetails(securityLevel: KeySecurityLevel) {
    val organizationLabel = securityLevel.organizationLabel
    Column {
        LinkText(
            prefix = "Try out ",
            link = "this android-key attestation inspector and look for:",
            url = "https://pedrooaugusto.github.io/android-key-attestation-inspector/",
        )
        AttestationBullet("leaf certificate fingerpint matches public key fingerprint")
        AttestationBullet("Google root certificate")
        AttestationBullet("O=$organizationLabel")
        AttestationBullet("extension 1.3.6.1.4.1.11129.2.1.17 (Android Key Attestation)")
        Text(
            buildAnnotatedString {
                val code = SpanStyle(fontFamily = FontFamily.Monospace)
                append("•  ")
                withStyle(code) { append("attestationApplicationId") }
                append(" names package ")
                withStyle(code) { append("app.keyholm") }
                append(" and Keyholm's signing certificate hash")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        LinkText(
            prefix = "See ",
            link = "Google docs about verification",
            url = "https://developer.android.com/privacy-and-security/security-key-attestation",
            suffix = " for more information.",
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
internal fun AttestationDialog(
    attestation: Loadable<AttestationInfo>,
    securityLevel: KeySecurityLevel,
    fileName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pemCerts = if (attestation is Loadable.Loaded) attestation.value.pemCerts else emptyList()
    val pemText = pemCerts.joinToString("\n\n")
    val saveLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-pem-file"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val saved =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val stream = checkNotNull(context.contentResolver.openOutputStream(uri))
                            stream.use { it.write(pemText.toByteArray()) }
                        }.isSuccess
                    }
                if (!saved) Toast.makeText(context, "Couldn't save the file", Toast.LENGTH_LONG).show()
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attestation certificate") },
        text = {
            if (attestation is Loadable.Failed) Text(LOAD_ERROR) else AttestationDetails(securityLevel)
        },
        confirmButton = {
            if (pemCerts.isNotEmpty()) {
                Row {
                    TextButton(onClick = { saveLauncher.launch(fileName) }) { Text("Save to file") }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip =
                                ClipData.newPlainText("attestation", pemText).apply {
                                    description.extras =
                                        PersistableBundle().apply {
                                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                        }
                                }
                            clipboard.setPrimaryClip(clip)
                        },
                    ) { Text("Copy") }
                }
            }
        },
    )
}
