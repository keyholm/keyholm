package app.keyholm.store

import app.keyholm.webauthn.AssetLinkStatement
import app.keyholm.webauthn.CertFingerprint
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RpId
import java.time.Instant

private const val MAX_DENIED_ENTRIES = 50

data class DeniedNativeAppKey(
    val rpId: RpId,
    val packageName: PackageName,
    val certFingerprints: Set<CertFingerprint>,
)

data class DeniedNativeAppInfo(
    val lastDeniedAt: Instant,
    val denialCount: Int,
)

data class DeniedNativeApps(
    val denied: Map<DeniedNativeAppKey, DeniedNativeAppInfo> = emptyMap(),
    val accepted: Map<RpId, List<AssetLinkStatement>> = emptyMap(),
)

internal fun List<AssetLinkStatement>.grants(
    packageName: PackageName,
    fingerprints: Set<CertFingerprint>,
): Boolean =
    fingerprints.isNotEmpty() &&
        any { statement ->
            statement.packageName == packageName && statement.fingerprints.containsAll(fingerprints)
        }

internal fun DeniedNativeApps.recordDenial(
    key: DeniedNativeAppKey,
    at: Instant,
): DeniedNativeApps {
    if (accepted[key.rpId]?.grants(key.packageName, key.certFingerprints) == true) return this
    val count = (denied[key]?.denialCount ?: 0) + 1
    val trimmed =
        (denied + (key to DeniedNativeAppInfo(at, count)))
            .entries
            .sortedByDescending { it.value.lastDeniedAt }
            .take(MAX_DENIED_ENTRIES)
            .associate { it.toPair() }
    return copy(denied = trimmed)
}

internal fun DeniedNativeApps.approve(key: DeniedNativeAppKey): DeniedNativeApps =
    copy(denied = denied - key, accepted = accepted.allowing(key))

internal fun DeniedNativeApps.dismiss(key: DeniedNativeAppKey): DeniedNativeApps = copy(denied = denied - key)

internal fun DeniedNativeApps.revoke(
    key: DeniedNativeAppKey,
    at: Instant,
): DeniedNativeApps {
    val next = accepted.disallowing(key)
    if (next == accepted) return this
    return copy(accepted = next).recordDenial(key, at)
}

internal fun DeniedNativeApps.exceptionKeys(): List<DeniedNativeAppKey> = accepted.toExceptionKeys()

internal fun Map<RpId, List<AssetLinkStatement>>.toExceptionKeys(): List<DeniedNativeAppKey> =
    flatMap { (rpId, statements) ->
        statements.map { DeniedNativeAppKey(rpId, it.packageName, it.fingerprints) }
    }.sortedWith(
        compareBy(
            { it.rpId.value },
            { it.packageName.value },
            {
                it.certFingerprints
                    .map { fp -> fp.colonHex }
                    .sorted()
                    .joinToString(",")
            },
        ),
    )

private fun Map<RpId, List<AssetLinkStatement>>.allowing(key: DeniedNativeAppKey): Map<RpId, List<AssetLinkStatement>> {
    val statements = this[key.rpId].orEmpty()
    if (statements.grants(key.packageName, key.certFingerprints)) return this
    val (matching, others) = statements.partition { it.packageName == key.packageName }
    val fingerprints = matching.firstOrNull()?.fingerprints.orEmpty() + key.certFingerprints
    val updated = others + AssetLinkStatement(key.packageName, fingerprints)
    return this + (key.rpId to updated)
}

private fun Map<RpId, List<AssetLinkStatement>>.disallowing(key: DeniedNativeAppKey): Map<RpId, List<AssetLinkStatement>> {
    val statements = this[key.rpId] ?: return this
    val updated =
        statements.mapNotNull { statement ->
            if (statement.packageName != key.packageName) return@mapNotNull statement
            val fingerprints = statement.fingerprints - key.certFingerprints
            fingerprints.takeIf { it.isNotEmpty() }?.let { AssetLinkStatement(key.packageName, it) }
        }
    return if (updated.isEmpty()) this - key.rpId else this + (key.rpId to updated)
}
