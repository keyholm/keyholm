package app.keyholm.store

import android.net.Uri
import app.keyholm.util.B64
import app.keyholm.webauthn.RpId
import co.touchlab.kermit.Logger
import com.google.common.truth.Truth.assertThat
import com.upokecenter.cbor.CBORObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationExportEntryTest {
    private val log = Logger.withTag("test")

    private fun migrateUri(param: String) = Uri.parse("keyholm://migrate?d=$param")

    @Test
    fun `migrationImportEntries rejects a non-keyholm scheme`() {
        assertThat(migrationImportEntries(log, Uri.parse("https://migrate?d=AA")))
            .isEqualTo(MigrationImport.NotAMigrationLink)
    }

    @Test
    fun `migrationImportEntries rejects the wrong host`() {
        assertThat(migrationImportEntries(log, Uri.parse("keyholm://export?d=AA")))
            .isEqualTo(MigrationImport.NotAMigrationLink)
    }

    @Test
    fun `migrationImportEntries rejects a missing d parameter`() {
        assertThat(migrationImportEntries(log, Uri.parse("keyholm://migrate"))).isEqualTo(MigrationImport.Malformed)
    }

    @Test
    fun `migrationImportEntries rejects a malformed base64 d parameter`() {
        assertThat(migrationImportEntries(log, migrateUri("A"))).isEqualTo(MigrationImport.Malformed)
    }

    @Test
    fun `migrationImportEntries rejects valid base64 that is not CBOR`() {
        assertThat(migrationImportEntries(log, migrateUri(B64.enc(byteArrayOf(0xff.toByte())))))
            .isEqualTo(MigrationImport.Malformed)
    }

    @Test
    fun `migrationImportEntries rejects a CBOR map instead of an array`() {
        val cbor = CBORObject.NewMap().Add("rpId", "example.com")

        assertThat(migrationImportEntries(log, migrateUri(B64.enc(cbor.EncodeToBytes()))))
            .isEqualTo(MigrationImport.Malformed)
    }

    @Test
    fun `migrationImportEntries rejects array entries of the wrong shape`() {
        val cbor = CBORObject.NewArray().Add("just a string")

        assertThat(migrationImportEntries(log, migrateUri(B64.enc(cbor.EncodeToBytes()))))
            .isEqualTo(MigrationImport.Malformed)
    }

    @Test
    fun `migrationImportEntries rejects an entry with too few fields`() {
        val cbor = CBORObject.NewArray().Add(CBORObject.NewArray().Add("example.com").Add("alice"))

        assertThat(migrationImportEntries(log, migrateUri(B64.enc(cbor.EncodeToBytes()))))
            .isEqualTo(MigrationImport.Malformed)
    }

    @Test
    fun `migrationImportEntries rejects an empty entry list`() {
        val cbor = CBORObject.NewArray()

        assertThat(migrationImportEntries(log, migrateUri(B64.enc(cbor.EncodeToBytes()))))
            .isEqualTo(MigrationImport.Malformed)
    }

    @Test
    fun `migrationImportEntries round-trips a valid export`() {
        val entries =
            listOf(
                MigrationExportEntry(
                    rpId = RpId("example.com"),
                    rpName = "Example",
                    userName = "alice",
                    displayName = "Alice",
                    createdAt = 42L,
                ),
            )

        assertThat(migrationImportEntries(log, Uri.parse(migrationExportUri(entries))))
            .isEqualTo(MigrationImport.Entries(entries))
    }
}
