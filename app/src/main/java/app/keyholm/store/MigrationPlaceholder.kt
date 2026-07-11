package app.keyholm.store

import androidx.compose.runtime.Immutable
import app.keyholm.webauthn.RpId
import java.time.Instant

// Add @Immutable just like for PasskeyRecord
// it's captured in MigrationPlaceholderItem's swipe onDismiss
// lambda and re-emitted as a fresh instance by DataStore.
// Strong skipping would otherwise compares it by identity there and the swipe can re-fire.
@Immutable
data class MigrationPlaceholder(
    val rpId: RpId,
    val userName: String,
    val displayName: String,
    val originalCreatedAt: Instant,
)
