package app.keyholm.ui.main

import app.keyholm.store.MigrationPlaceholder
import app.keyholm.store.MigrationRepository
import app.keyholm.store.RecordLifecycle
import app.keyholm.webauthn.RpId
import kotlinx.coroutines.flow.Flow

class PlaceholderPendingDeletes(
    private val repo: MigrationRepository,
) : PendingDeleteStore<Pair<RpId, String>, MigrationPlaceholder> {
    override val records: Flow<List<MigrationPlaceholder>> = repo.placeholders

    override fun id(record: MigrationPlaceholder) = record.rp.id to record.userName

    override fun lifecycle(record: MigrationPlaceholder) = record.lifecycle

    override fun withLifecycle(
        record: MigrationPlaceholder,
        lifecycle: RecordLifecycle,
    ) = record.copy(lifecycle = lifecycle)

    override suspend fun update(record: MigrationPlaceholder) = repo.update(record)

    override suspend fun delete(id: Pair<RpId, String>) = repo.delete(id.first, id.second)

    // Placeholders have no key material, so there's nothing to confirm or clean up
    override fun planDelete(record: MigrationPlaceholder) = DeletePlan.Orphaned

    override fun deleteKeyMaterial(record: MigrationPlaceholder) = Unit
}
