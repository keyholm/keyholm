package app.keyholm.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.keyholm.ui.common.BackButton
import app.keyholm.ui.common.Section
import app.keyholm.ui.common.rememberAppIcon
import app.keyholm.ui.main.settings.SECTION_TITLE_ACCOUNT_MIGRATION
import app.keyholm.ui.main.settings.SECTION_TITLE_CONTRIBUTE
import app.keyholm.ui.main.settings.SECTION_TITLE_KEY_CREATION
import app.keyholm.ui.main.settings.SECTION_TITLE_SIGNATURE_ALGORITHMS
import app.keyholm.ui.main.settings.SECTION_TITLE_TROUBLESHOOTING
import app.keyholm.ui.main.settings.SECTION_TITLE_TRUST_BEHAVIOR
import app.keyholm.ui.main.settings.TITLE_EXCEPTIONS
import app.keyholm.ui.theme.titleColor

private const val WEBAUTHN_SPEC = "https://www.w3.org/TR/webauthn-3/"
private const val WEBAUTHN_AAGUID = "https://www.w3.org/TR/webauthn-3/#aaguid"
private const val WEBAUTHN_SINGLE_DEVICE_CREDENTIAL =
    "https://www.w3.org/TR/webauthn-3/#single-device-credential"
private const val WEBAUTHN_DISCOVERABLE_CREDENTIAL =
    "https://www.w3.org/TR/webauthn-3/#client-side-discoverable-credential"
private const val WEBAUTHN_ATTESTATION = "https://www.w3.org/TR/webauthn-3/#sctn-attestation"
private const val WEBAUTHN_PRF_EXTENSION =
    "https://w3c.github.io/webauthn/#prf-extension"
private const val ANDROID_DIGITAL_ASSET_LINKS = "https://developers.google.com/digital-asset-links/v1/getting-started"
private const val ANDROID_KEYSTORE = "https://developer.android.com/privacy-and-security/keystore"
private const val ANDROID_STRONGBOX =
    "https://developer.android.com/privacy-and-security/keystore#strongbox_keymint_secure_element"
private const val ANDROID_TEE = "https://source.android.com/docs/security/features/trusty"
private const val ANDROID_BIOMETRIC_AUTH = "https://developer.android.com/identity/sign-in/biometric-auth"
private const val ANDROID_DEVICE_CREDENTIAL =
    "https://developer.android.com/identity/sign-in/biometric-auth#allow-fallback"

private fun String.reflowParagraphs(): String = split(Regex("\n{2,}")).joinToString("\n") { it.replace('\n', ' ') }

private val INLINE_MARKUP = Regex("""\[([^]]+)]\(([^)]+)\)|\*\*([^*]+)\*\*""")

@Composable
private fun linkedText(raw: String): AnnotatedString {
    val text = raw.reflowParagraphs()
    return remember(text) {
        buildAnnotatedString {
            var cursor = 0
            for (match in INLINE_MARKUP.findAll(text)) {
                append(text.substring(cursor, match.range.first))
                val (linkLabel, url, boldText) = match.destructured
                if (url.isNotEmpty()) {
                    withLink(LinkAnnotation.Url(url)) { append(linkLabel) }
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(boldText) }
                }
                cursor = match.range.last + 1
            }
            append(text.substring(cursor))
        }
    }
}

private val HELP_INTRO =
    """
    Keyholm uses Android's [secure hardware]($ANDROID_KEYSTORE) to generate
    [single-device]($WEBAUTHN_SINGLE_DEVICE_CREDENTIAL), [client-side discoverable credentials]($WEBAUTHN_DISCOVERABLE_CREDENTIAL).
    Due to the properties of this hardware, they can never leave the device
    and they are secured by biometrics and/or device credentials, like the device itself.

    There are two classes of secure hardware. The dedicated hardware is known as the [StrongBox]($ANDROID_STRONGBOX).
    The [Trusted Execution Environment (TEE)]($ANDROID_TEE) is part of the main processor.

    [Attestation]($WEBAUTHN_ATTESTATION) is supported. Attestation certificates can be viewed from Keyholm.
    Every relying party must check the attestation against the key it's actually presented.

    The [PRF extension]($WEBAUTHN_PRF_EXTENSION) is supported and always uses the dedicated secure hardware.
    Using PRF requires a second prompt.

    Keyholm is focused on compatibility with Pixel devices. Any changes to support other devices are welcome,
    but it only ever supports the latest Android version.

    Keyholm does not, cannot and will never use the network.
    """.trimIndent()

private val HELP_BODY_TRUST_BEHAVIOR =
    """
    Native app passkey usage is special. The exact behavior is not fully specified by the [WebAuthn spec]($WEBAUTHN_SPEC).

    Android suggests the use of [Digital Asset Links]($ANDROID_DIGITAL_ASSET_LINKS).
    These are a way for an RP to restrict the set of apps that can use passkeys with that RP.
    Being strictly compliant with this would require Keyholm to have network access.

    Instead, any time an app is denied, it appears under $TITLE_EXCEPTIONS and can be allowed or forgotten.

    In addition, Keyholm embeds a set of Digital Asset Links at build time for a community-maintained set of RPs.
    Please open an issue/PR against the repo to add your apps/RPs to the set!

    You can also start with an empty set or even just allow all apps, though note this latter option
    could lead to a compromise of your credentials by an untrustworthy app.

    The Digital Asset Links paradigm is a client-side UX improvement. In any case,
    Keyholm embeds the fingerprint of the native app's signing key in its response that the RP can and should check.
    """.trimIndent()

private val HELP_BODY_KEY_CREATION =
    """
    Keyholm, by default, sends a unique GUID, called the [AAGUID]($WEBAUTHN_AAGUID), to indentify itself to the RP.
    This can be disabled. Note that attestation always reveals Keyholm as the authenticator.

    Every key can be protected either by only [biometrics]($ANDROID_BIOMETRIC_AUTH),
    by only [device credentials]($ANDROID_DEVICE_CREDENTIAL) or by either one.

    Note that, by default, any key that uses biometrics only is **permanently invalidated** if biometrics enrollment changes.
    Disabling the lock screen always invalidates keys enforced with credentials.
    """.trimIndent()

private val HELP_BODY_SIGNATURE_ALGORITHMS =
    """
    You can configure Keyholm to prefer the use of a specific algorithm over the preferences of the relying party.
    A fallback preference is also available. The choice of ML-DSA algorithm, if enabled, is similarly configurable.
    ML-DSA-65 cannot be explicitly selected over ML-DSA-87.

    It's almost guaranteed that only ES256 is available on the dedicated secure element.
    Enabling other algorithms entails falling back to the TEE.
    In particular, this is the only way to use ed25519/ML-DSA keys.

    ES256 keys as well as PRF enforce use of the dedicated hardware.
    """.trimIndent()

private val HELP_BODY_ACCOUNT_MIGRATION =
    """
    Keyholm passkeys are unexportable.

    However, when migrating devices it's convenient to have a list of accounts that need to have a new passkey created.
    The export/import functionality adds a list of exported placeholder items to the importing device.

    The QR code export should function up until at least ~40 passkeys.
    """.trimIndent()

private val HELP_BODY_TROUBLESHOOTING =
    """
    If you're trying to create a passkey and Keyholm doesn't appear in the list,
    it's possible that the list of algorithms supported by the RP
    and the list of algorithms supported and enabled by Keyholm don't intersect.

    You can try enabling all of them and setting "Always ask" for algorithm preference.
    This would at least reveal if any algorithm at all is supported by Keyholm.
    """.trimIndent()

private val HELP_BODY_CONTRIBUTE =
    """
    Any and all contributions are welcome!

    If you run into any UX, security or WebAuthn spec compliance problems,
    please create an issue on the above GitHub repo!
    """.trimIndent()

private data class HelpSection(
    val title: String,
    val body: String,
)

private val HELP_SECTIONS =
    listOf(
        HelpSection(SECTION_TITLE_TRUST_BEHAVIOR, HELP_BODY_TRUST_BEHAVIOR),
        HelpSection(SECTION_TITLE_KEY_CREATION, HELP_BODY_KEY_CREATION),
        HelpSection(SECTION_TITLE_SIGNATURE_ALGORITHMS, HELP_BODY_SIGNATURE_ALGORITHMS),
        HelpSection(SECTION_TITLE_ACCOUNT_MIGRATION, HELP_BODY_ACCOUNT_MIGRATION),
        HelpSection(SECTION_TITLE_TROUBLESHOOTING, HELP_BODY_TROUBLESHOOTING),
        HelpSection(SECTION_TITLE_CONTRIBUTE, HELP_BODY_CONTRIBUTE),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text("Help") },
        navigationIcon = {
            BackButton(onBack)
        },
    )
}

@Composable
private fun HelpSectionView(section: HelpSection) {
    Section(title = section.title) {
        item { shape ->
            ListItem(
                selected = false,
                onClick = {},
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shapes = ListItemDefaults.shapes(shape = shape),
            ) {
                Text(linkedText(section.body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private const val SECTION_TITLE_ABOUT = "About"
private const val LABEL_VERSION = "Version"
private const val LABEL_GITHUB = "GitHub"
private const val DESCRIPTION_GITHUB = "Source code, issues and information"
private const val LABEL_LICENSE = "License"
private const val DESCRIPTION_LICENSE = "Keyholm is licensed under GPLv3"
private const val GITHUB_URL = "https://github.com/keyholm/keyholm"
private const val LICENSE_URL = "https://github.com/keyholm/keyholm/blob/main/LICENSE"

private val LEADING_WIDTH = 40.dp

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        info.versionName.orEmpty()
    }
}

@Composable
private fun AboutRow(
    icon: ImageVector,
    label: String,
    detail: String,
    external: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Box(Modifier.width(LEADING_WIDTH), contentAlignment = Alignment.CenterStart) {
            Icon(icon, contentDescription = null)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (external) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = appVersion()
    Section(title = SECTION_TITLE_ABOUT) {
        item { shape ->
            Column(
                Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = rememberAppIcon(),
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_WIDTH).clip(CircleShape),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Keyholm",
                        style = MaterialTheme.typography.titleLarge,
                        color = titleColor,
                    )
                }
                Spacer(Modifier.height(8.dp))
                AboutRow(Icons.Outlined.Info, LABEL_VERSION, version) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(LABEL_VERSION, version))
                }
                Spacer(Modifier.height(8.dp))
                AboutRow(Icons.Outlined.Code, LABEL_GITHUB, DESCRIPTION_GITHUB, external = true) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri()))
                }
                Spacer(Modifier.height(8.dp))
                AboutRow(Icons.Outlined.Description, LABEL_LICENSE, DESCRIPTION_LICENSE, external = true) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, LICENSE_URL.toUri()))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(topBar = { HelpTopBar(onBack) }) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding),
        ) {
            AboutSection()
            Section(title = null) {
                item { shape ->
                    ListItem(
                        selected = false,
                        onClick = {},
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shapes = ListItemDefaults.shapes(shape = shape),
                    ) {
                        Text(linkedText(HELP_INTRO), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            HELP_SECTIONS.forEach { section -> HelpSectionView(section) }
        }
    }
}
