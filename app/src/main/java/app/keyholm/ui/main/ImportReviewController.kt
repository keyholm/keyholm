package app.keyholm.ui.main

import app.keyholm.store.ImportResult
import app.keyholm.store.MigrationExportEntry
import app.keyholm.store.MigrationPlaceholder
import app.keyholm.store.MigrationRepository
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.RecordLifecycle
import app.keyholm.store.readStore
import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.RpId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface ImportReview {
    val entries: List<MigrationExportEntry>

    data class DeepLink(
        override val entries: List<MigrationExportEntry>,
    ) : ImportReview

    data class InApp(
        override val entries: List<MigrationExportEntry>,
    ) : ImportReview
}

data class ImportPreview(
    val review: ImportReview,
    val fresh: List<MigrationPlaceholder>,
    val duplicatePasskeys: List<MigrationPlaceholder>,
    val duplicatePlaceholders: List<MigrationPlaceholder>,
)

private data class ExistingKeys(
    val passkeys: Set<Pair<RpId, String>>,
    val placeholders: Set<Pair<RpId, String>>,
) {
    operator fun contains(key: Pair<RpId, String>) = key in passkeys || key in placeholders
}

class ImportReviewController(
    private val migrationRepo: MigrationRepository,
    private val passkeyRepo: PasskeyRepository,
    private val scope: CoroutineScope,
) {
    private val _preview = MutableStateFlow<ImportPreview?>(null)
    val preview: StateFlow<ImportPreview?> = _preview.asStateFlow()

    fun request(
        review: ImportReview,
        onFailure: () -> Unit,
    ) {
        scope.launch {
            val existing = existingKeys() ?: return@launch onFailure()
            val parsed = review.entries.map { it.toPlaceholder() }
            val (duplicatePasskeys, rest) = parsed.partition { (it.rp.id to it.userName) in existing.passkeys }
            val (duplicatePlaceholders, fresh) = rest.partition { (it.rp.id to it.userName) in existing.placeholders }
            _preview.value = ImportPreview(review, fresh, duplicatePasskeys, duplicatePlaceholders)
        }
    }

    fun dismiss() {
        _preview.value = null
    }

    fun importList(
        entries: List<MigrationExportEntry>,
        onResult: (ImportResult) -> Unit,
        onFailure: () -> Unit,
    ) {
        scope.launch {
            val parsed = entries.map { it.toPlaceholder() }
            val existing = existingKeys() ?: return@launch onFailure()
            val fresh = parsed.filter { (it.rp.id to it.userName) !in existing }
            migrationRepo.addAll(fresh).fold(
                onSuccess = { onResult(ImportResult(fresh.size, parsed.size - fresh.size)) },
                onFailure = { onFailure() },
            )
        }
    }

    private suspend fun existingKeys(): ExistingKeys? =
        readStore {
            ExistingKeys(
                passkeys =
                    passkeyRepo.passkeys
                        .first()
                        .map { it.rp.id to it.user.name }
                        .toSet(),
                placeholders =
                    migrationRepo.placeholders
                        .first()
                        .map { it.rp.id to it.userName }
                        .toSet(),
            )
        }
}

private fun MigrationExportEntry.toPlaceholder() =
    MigrationPlaceholder(
        rp = RelyingParty(id = rpId, name = rpName),
        userName = userName,
        displayName = displayName,
        originalCreatedAt = Instant.ofEpochMilli(createdAt),
        lifecycle = RecordLifecycle.Active,
    )
