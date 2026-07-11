package app.keyholm.ui.main

import app.keyholm.store.ImportResult
import app.keyholm.store.MigrationExportEntry
import app.keyholm.store.MigrationPlaceholder
import app.keyholm.store.MigrationRepository
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.readStore
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

class ImportReviewController(
    private val migrationRepo: MigrationRepository,
    private val passkeyRepo: PasskeyRepository,
    private val scope: CoroutineScope,
) {
    private val _review = MutableStateFlow<ImportReview?>(null)
    val review: StateFlow<ImportReview?> = _review.asStateFlow()

    fun request(review: ImportReview) {
        _review.value = review
    }

    fun dismiss() {
        _review.value = null
    }

    fun importList(
        entries: List<MigrationExportEntry>,
        onResult: (ImportResult) -> Unit,
        onFailure: () -> Unit,
    ) {
        scope.launch {
            val parsed =
                entries.map {
                    MigrationPlaceholder(
                        rpId = it.rpId,
                        userName = it.userName,
                        displayName = it.displayName,
                        originalCreatedAt = Instant.ofEpochMilli(it.createdAt),
                    )
                }
            val existing =
                readStore {
                    (
                        passkeyRepo.passkeys.first().map { it.rp.id to it.user.name } +
                            migrationRepo.placeholders.first().map { it.rpId to it.userName }
                    ).toSet()
                } ?: return@launch onFailure()
            val fresh = parsed.filter { (it.rpId to it.userName) !in existing }
            migrationRepo.addAll(fresh).fold(
                onSuccess = { onResult(ImportResult(fresh.size, parsed.size - fresh.size)) },
                onFailure = { onFailure() },
            )
        }
    }
}
