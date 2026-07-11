package app.keyholm.store

import app.keyholm.store.proto.AcceptedAppListProto
import app.keyholm.store.proto.AcceptedAppProto
import app.keyholm.store.proto.DeniedNativeAppProto
import app.keyholm.store.proto.DeniedNativeAppsProto
import app.keyholm.webauthn.AssetLinkStatement
import app.keyholm.webauthn.CertFingerprint
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RpId

internal fun DeniedNativeAppsProto.toDomain(): DeniedNativeApps =
    DeniedNativeApps(
        denied = deniedList.associate { it.toKey() to it.toInfo() },
        accepted = acceptedMap.entries.associate { (rpId, list) -> RpId(rpId) to list.toStatements() },
    )

internal fun DeniedNativeApps.toProto(): DeniedNativeAppsProto =
    DeniedNativeAppsProto
        .newBuilder()
        .addAllDenied(denied.map { (key, info) -> key.toProto(info) })
        .putAllAccepted(accepted.entries.associate { (rpId, statements) -> rpId.value to statements.toProto() })
        .build()

private fun DeniedNativeAppProto.toKey() =
    DeniedNativeAppKey(
        RpId(rpId),
        PackageName(packageName),
        CertFingerprint.of(sha256CertFingerprintsHexList),
    )

private fun DeniedNativeAppProto.toInfo() = DeniedNativeAppInfo(lastDeniedAt.toInstant(), denialCount)

private fun DeniedNativeAppKey.toProto(info: DeniedNativeAppInfo): DeniedNativeAppProto =
    DeniedNativeAppProto
        .newBuilder()
        .setRpId(rpId.value)
        .setPackageName(packageName.value)
        .addAllSha256CertFingerprintsHex(certFingerprints.map { it.colonHex })
        .setLastDeniedAt(info.lastDeniedAt.toTimestamp())
        .setDenialCount(info.denialCount)
        .build()

private fun AcceptedAppListProto.toStatements(): List<AssetLinkStatement> =
    appsList
        .groupBy { PackageName(it.packageName) }
        .map { (pkg, apps) ->
            AssetLinkStatement(pkg, CertFingerprint.of(apps.map { it.sha256CertFingerprintHex }))
        }

private fun List<AssetLinkStatement>.toProto(): AcceptedAppListProto =
    AcceptedAppListProto
        .newBuilder()
        .addAllApps(
            flatMap { statement ->
                statement.fingerprints.map { fingerprint ->
                    AcceptedAppProto
                        .newBuilder()
                        .setPackageName(statement.packageName.value)
                        .setSha256CertFingerprintHex(fingerprint.colonHex)
                        .build()
                }
            },
        ).build()
