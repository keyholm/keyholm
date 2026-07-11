package app.keyholm.store

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.keyholm.store.proto.DeniedNativeAppsProto
import app.keyholm.store.proto.MigrationPlaceholdersProto
import app.keyholm.store.proto.PasskeyRecordsProto
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

internal object PasskeyRecordsSerializer : Serializer<PasskeyRecordsProto> {
    override val defaultValue: PasskeyRecordsProto = PasskeyRecordsProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): PasskeyRecordsProto =
        try {
            PasskeyRecordsProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("passkeys store corrupt", e)
        }

    override suspend fun writeTo(
        t: PasskeyRecordsProto,
        output: OutputStream,
    ) = t.writeTo(output)
}

internal object MigrationPlaceholdersSerializer : Serializer<MigrationPlaceholdersProto> {
    override val defaultValue: MigrationPlaceholdersProto = MigrationPlaceholdersProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): MigrationPlaceholdersProto =
        try {
            MigrationPlaceholdersProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("migration placeholders store corrupt", e)
        }

    override suspend fun writeTo(
        t: MigrationPlaceholdersProto,
        output: OutputStream,
    ) = t.writeTo(output)
}

internal object DeniedNativeAppsSerializer : Serializer<DeniedNativeAppsProto> {
    override val defaultValue: DeniedNativeAppsProto = DeniedNativeAppsProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): DeniedNativeAppsProto =
        try {
            DeniedNativeAppsProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("denied native apps store corrupt", e)
        }

    override suspend fun writeTo(
        t: DeniedNativeAppsProto,
        output: OutputStream,
    ) = t.writeTo(output)
}
