package app.keyholm.store.encrypted

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.keyholm.store.proto.StoreKeysetProto
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

internal object StoreKeysetSerializer : Serializer<StoreKeysetProto> {
    override val defaultValue: StoreKeysetProto = StoreKeysetProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): StoreKeysetProto =
        try {
            StoreKeysetProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("store keyset corrupt", e)
        }

    override suspend fun writeTo(
        t: StoreKeysetProto,
        output: OutputStream,
    ) = t.writeTo(output)
}
