package app.keyholm.store

import com.google.protobuf.Timestamp
import java.time.Instant

internal fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

internal fun Instant.toTimestamp(): Timestamp =
    Timestamp
        .newBuilder()
        .setSeconds(epochSecond)
        .setNanos(nano)
        .build()
