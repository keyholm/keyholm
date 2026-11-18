package app.keyholm.store.encrypted

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.keyholm.store.proto.DeviceKeysetProto
import app.keyholm.store.proto.StoreKeysetProto
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

internal object StoreKeysetSerializer : Serializer<StoredKeyset?> {
    override val defaultValue: StoredKeyset? = null

    override suspend fun readFrom(input: InputStream): StoredKeyset =
        try {
            StoreKeysetProto.parseFrom(input).toStoredKeyset()
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("store keyset corrupt", e)
        }

    override suspend fun writeTo(
        t: StoredKeyset?,
        output: OutputStream,
    ) = checkNotNull(t) { "the keyset is only written once it exists" }.toProto().writeTo(output)
}

private fun StoreKeysetProto.toStoredKeyset(): StoredKeyset =
    when (protectionCase) {
        StoreKeysetProto.ProtectionCase.DEVICE -> {
            StoredKeyset.Device(salt = device.salt, keyset = device.keyset)
        }

        StoreKeysetProto.ProtectionCase.PROTECTION_NOT_SET -> {
            throw CorruptionException("store keyset has no protection")
        }
    }

private fun StoredKeyset.toProto(): StoreKeysetProto =
    when (this) {
        is StoredKeyset.Device -> {
            StoreKeysetProto
                .newBuilder()
                .setDevice(DeviceKeysetProto.newBuilder().setSalt(salt).setKeyset(keyset))
                .build()
        }
    }
