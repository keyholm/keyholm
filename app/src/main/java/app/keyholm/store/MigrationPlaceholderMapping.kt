package app.keyholm.store

import app.keyholm.store.proto.MigrationPlaceholderProto
import app.keyholm.webauthn.RpId

internal fun MigrationPlaceholderProto.toDomain(): MigrationPlaceholder =
    MigrationPlaceholder(
        rpId = RpId(rpId),
        userName = userName,
        displayName = displayName,
        originalCreatedAt = originalCreatedAt.toInstant(),
    )

internal fun MigrationPlaceholder.toProto(): MigrationPlaceholderProto =
    MigrationPlaceholderProto
        .newBuilder()
        .setRpId(rpId.value)
        .setUserName(userName)
        .setDisplayName(displayName)
        .setOriginalCreatedAt(originalCreatedAt.toTimestamp())
        .build()
