package app.keyholm.store

import android.net.Uri
import app.keyholm.util.B64
import app.keyholm.webauthn.RpId
import co.touchlab.kermit.Logger
import com.upokecenter.cbor.CBORException
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import kotlinx.serialization.Serializable

@Serializable
data class MigrationExportEntry(
    val rpId: RpId,
    val userName: String,
    val displayName: String,
    val createdAt: Long,
)

private const val MIGRATION_URI_SCHEME = "keyholm"
private const val MIGRATION_URI_HOST = "migrate"
private const val MIGRATION_URI_PARAM = "d"

// More than enough to hold a QR code
private const val MAX_ENCODED_LENGTH = 8 * 1024

private const val CBOR_INDEX_RP_ID = 0
private const val CBOR_INDEX_USER_NAME = 1
private const val CBOR_INDEX_DISPLAY_NAME = 2
private const val CBOR_INDEX_CREATED_AT = 3
private const val CBOR_ENTRY_FIELDS = 4

private fun MigrationExportEntry.toCbor(): CBORObject =
    CBORObject
        .NewArray()
        .Add(rpId.value)
        .Add(userName)
        .Add(displayName)
        .Add(createdAt)

private fun CBORObject.toMigrationExportEntry(): MigrationExportEntry {
    require(size() == CBOR_ENTRY_FIELDS) { "a migration entry has ${size()} fields, expected $CBOR_ENTRY_FIELDS" }
    return MigrationExportEntry(
        rpId = RpId(get(CBOR_INDEX_RP_ID).AsString()),
        userName = get(CBOR_INDEX_USER_NAME).AsString(),
        displayName = get(CBOR_INDEX_DISPLAY_NAME).AsString(),
        createdAt = get(CBOR_INDEX_CREATED_AT).AsInt64Value(),
    )
}

fun migrationExportUri(entries: List<MigrationExportEntry>): String {
    val array = CBORObject.NewArray()
    entries.forEach { array.Add(it.toCbor()) }
    val encoded = B64.enc(array.EncodeToBytes())
    return "$MIGRATION_URI_SCHEME://$MIGRATION_URI_HOST?$MIGRATION_URI_PARAM=$encoded"
}

sealed interface MigrationImport {
    data class Entries(
        val entries: List<MigrationExportEntry>,
    ) : MigrationImport

    data object NotAMigrationLink : MigrationImport

    data object Malformed : MigrationImport
}

fun migrationImportEntries(
    log: Logger,
    uri: Uri,
): MigrationImport {
    if (uri.scheme != MIGRATION_URI_SCHEME || uri.host != MIGRATION_URI_HOST) {
        return MigrationImport.NotAMigrationLink
    }
    val encoded = uri.getQueryParameter(MIGRATION_URI_PARAM) ?: return MigrationImport.Malformed
    if (encoded.length > MAX_ENCODED_LENGTH) return MigrationImport.Malformed
    val entries =
        try {
            val array = CBORObject.DecodeFromBytes(B64.dec(encoded))
            require(array.type == CBORType.Array) { "the migration payload is not a CBOR array" }
            (0 until array.size()).map { array.get(it).toMigrationExportEntry() }
        } catch (e: CBORException) {
            log.w(e) { "the migration link isn't valid CBOR" }
            null
        } catch (e: IllegalArgumentException) {
            log.w(e) { "the migration link isn't valid base64url, or an entry is malformed" }
            null
        }
    return if (entries.isNullOrEmpty()) MigrationImport.Malformed else MigrationImport.Entries(entries)
}

data class ImportResult(
    val imported: Int,
    val skipped: Int,
)

fun migrationImportResultMessage(result: ImportResult): String =
    "Imported ${result.imported} account${if (result.imported == 1) "" else "s"}" +
        if (result.skipped > 0) ", skipped ${result.skipped} already known" else ""
