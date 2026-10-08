package app.keyholm.store

import app.keyholm.store.proto.MigrationPlaceholderProto
import app.keyholm.store.proto.RelyingPartyProto

internal fun MigrationPlaceholderProto.toDomain(): MigrationPlaceholder =
    MigrationPlaceholder(
        rp = rp.toDomain(),
        userName = userName,
        displayName = displayName,
        originalCreatedAt = originalCreatedAt.toInstant(),
        lifecycle =
            if (hasPendingDeleteAt()) {
                RecordLifecycle.PendingDelete(pendingDeleteAt.toInstant())
            } else {
                RecordLifecycle.Active
            },
    )

internal fun MigrationPlaceholder.toProto(): MigrationPlaceholderProto {
    val builder =
        MigrationPlaceholderProto
            .newBuilder()
            .setRp(RelyingPartyProto.newBuilder().setId(rp.id.value).setName(rp.name))
            .setUserName(userName)
            .setDisplayName(displayName)
            .setOriginalCreatedAt(originalCreatedAt.toTimestamp())
    if (lifecycle is RecordLifecycle.PendingDelete) builder.pendingDeleteAt = lifecycle.at.toTimestamp()
    return builder.build()
}
