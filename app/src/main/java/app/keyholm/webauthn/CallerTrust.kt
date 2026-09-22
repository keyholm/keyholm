package app.keyholm.webauthn

import android.content.Context
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.credentials.provider.CallingAppInfo
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.util.B64
import app.keyholm.util.sha256
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val ALLOWLIST_FILE = "privileged_allowlist.json"

private val log = Logger.withTag("app.keyholm.webauthn.CallerTrust")

sealed interface Caller {
    data object Untrusted : Caller

    sealed interface Trusted : Caller

    sealed interface Privileged : Trusted {
        data class Client(
            val hash: ClientDataHash,
        ) : Privileged

        data class Origin(
            val origin: String,
        ) : Privileged
    }

    data class Normal(
        val origin: String,
    ) : Trusted
}

internal sealed interface TrustDecision {
    data class Allowed(
        val caller: Caller.Trusted,
    ) : TrustDecision

    sealed interface Denied : TrustDecision {
        val message: String

        data class NativeApp(
            override val message: String,
            val ref: DeniedNativeAppRef,
        ) : Denied

        data class Other(
            override val message: String,
        ) : Denied
    }
}

internal data class DeniedNativeAppRef(
    val rpId: RpId,
    val packageName: PackageName,
    val certFingerprints: Set<CertFingerprint>,
)

internal fun List<AssetLinkStatement>.grantsCaller(
    packageName: PackageName,
    signingInfo: SigningInfo,
): Boolean = any { it.packageName == packageName && signerFingerprintsMatch(signingInfo, it.fingerprints) }

internal enum class GrantSource { User, Community, None }

internal fun Caller.applyNativeAppTrust(
    trust: NativeAppTrust,
    ref: DeniedNativeAppRef,
    grant: GrantSource,
): TrustDecision =
    when (this) {
        is Caller.Untrusted -> TrustDecision.Denied.Other(ErrorMessages.CALLER_UNVERIFIED)
        is Caller.Normal -> nativeAppTrustDecision(trust, ref, grant)
        is Caller.Privileged -> TrustDecision.Allowed(this)
    }

private fun Caller.Normal.nativeAppTrustDecision(
    trust: NativeAppTrust,
    ref: DeniedNativeAppRef,
    grant: GrantSource,
): TrustDecision {
    if (grant == GrantSource.User) return TrustDecision.Allowed(this)
    return when (trust) {
        NativeAppTrust.AllowAll -> {
            TrustDecision.Allowed(this)
        }

        NativeAppTrust.DenyAll -> {
            TrustDecision.Denied.NativeApp(ErrorMessages.NATIVE_APP_TRUST_DENIED, ref)
        }

        is NativeAppTrust.Community -> {
            if (grant == GrantSource.Community) {
                TrustDecision.Allowed(this)
            } else {
                TrustDecision.Denied.NativeApp(ErrorMessages.CALLER_UNVERIFIED, ref)
            }
        }
    }
}

fun CallingAppInfo.androidOrigin(): String {
    val signers = signingInfo.apkContentsSigners.orEmpty()
    check(signers.isNotEmpty()) { "calling app $packageName has no APK signers" }
    val certHash = sha256(signers[0].toByteArray())
    return "android:apk-key-hash:${B64.enc(certHash)}"
}

fun CallingAppInfo.resolveCaller(
    privilegedAllowlistJson: String,
    providedHash: ClientDataHash?,
): Caller =
    try {
        val webOrigin = getOrigin(privilegedAllowlistJson)
        when {
            webOrigin == null -> Caller.Normal(androidOrigin())
            providedHash != null -> Caller.Privileged.Client(providedHash)
            else -> Caller.Privileged.Origin(webOrigin)
        }
    } catch (e: IllegalStateException) {
        log.w(e) { "resolveCaller failed, treating caller as untrusted" }
        Caller.Untrusted
    }

internal suspend fun Context.resolveTrustDecision(
    callingAppInfo: CallingAppInfo,
    rpId: RpId,
    trust: NativeAppTrust,
    userAccepted: Map<RpId, List<AssetLinkStatement>> = emptyMap(),
    providedHash: ClientDataHash? = null,
): TrustDecision {
    val allowlist = PrivilegedAllowlist.load(applicationContext)
    val caller = callingAppInfo.resolveCaller(allowlist, providedHash)
    val pkg = PackageName(callingAppInfo.packageName)
    val signingInfo = callingAppInfo.signingInfo
    val communityLinks = (trust as? NativeAppTrust.Community)?.assetLinksByDomain
    val grant =
        when {
            userAccepted[rpId]?.grantsCaller(pkg, signingInfo) == true -> GrantSource.User
            communityLinks?.get(rpId)?.grantsCaller(pkg, signingInfo) == true -> GrantSource.Community
            else -> GrantSource.None
        }
    return caller.applyNativeAppTrust(
        trust,
        DeniedNativeAppRef(rpId, pkg, callerSignerFingerprints(signingInfo)),
        grant,
    )
}

object PrivilegedAllowlist {
    @Volatile
    private var json: String? = null

    suspend fun load(context: Context): String =
        json ?: withContext(Dispatchers.IO) {
            context.applicationContext.assets
                .open(ALLOWLIST_FILE)
                .use { it.readBytes().toString(Charsets.UTF_8) }
        }.also { json = it }
}

private fun ByteArray.toColonHex(): String = joinToString(":") { "%02X".format(Locale.ROOT, it) }

private fun Signature.fingerprint(): CertFingerprint = CertFingerprint.of(sha256(toByteArray()).toColonHex())

fun callerSignerFingerprints(signingInfo: SigningInfo): Set<CertFingerprint> {
    val signers = signingInfo.apkContentsSigners.orEmpty().asList()
    val relevant = if (signingInfo.hasMultipleSigners()) signers else signers.take(1)
    return relevant.mapTo(mutableSetOf()) { it.fingerprint() }
}

fun signerFingerprintsMatch(
    signingInfo: SigningInfo,
    expected: Set<CertFingerprint>,
): Boolean =
    if (signingInfo.hasMultipleSigners()) {
        val signerHashes = callerSignerFingerprints(signingInfo)
        signerHashes.isNotEmpty() && expected.containsAll(signerHashes)
    } else {
        signingInfo.signingCertificateHistory.orEmpty().any { it.fingerprint() in expected }
    }
