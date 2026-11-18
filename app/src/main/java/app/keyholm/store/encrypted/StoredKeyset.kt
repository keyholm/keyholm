package app.keyholm.store.encrypted

import com.google.protobuf.ByteString

internal sealed interface StoredKeyset {
    data class Device(
        val salt: ByteString,
        val keyset: ByteString,
    ) : StoredKeyset
}
