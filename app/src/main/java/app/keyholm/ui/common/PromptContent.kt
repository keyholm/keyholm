package app.keyholm.ui.common

import android.text.format.DateUtils
import androidx.biometric.AuthenticationRequest
import androidx.biometric.PromptContentItemBulletedText
import androidx.biometric.PromptContentItemPlainText
import app.keyholm.store.PasskeyRecord
import app.keyholm.webauthn.RelyingParty
import java.time.Instant

internal fun promptContent(
    description: String,
    lastUsedAt: Instant?,
    rp: RelyingParty,
    preferRpName: Boolean,
    userLabel: String,
): AuthenticationRequest.BodyContent =
    AuthenticationRequest.BodyContent.VerticalList(
        description = description,
        // Pairs fill the prompt's two columns as a label/value table, a row each.
        items =
            buildList {
                add(PromptContentItemBulletedText("User"))
                add(PromptContentItemPlainText(userLabel))
                val idRow = "Relying Party ID" to rp.id.value
                val nameRow = rpDisplayName(rp)?.let { "Relying Party name" to it }
                val rpRows = if (preferRpName) listOfNotNull(nameRow, idRow) else listOfNotNull(idRow, nameRow)
                rpRows.forEach { (label, value) ->
                    add(PromptContentItemBulletedText(label))
                    add(PromptContentItemPlainText(value))
                }
                if (lastUsedAt != null) {
                    add(PromptContentItemBulletedText("Last used"))
                    add(PromptContentItemPlainText(lastUsedLabel(lastUsedAt)))
                }
            },
    )

internal fun promptContent(
    description: String,
    record: PasskeyRecord,
    preferRpName: Boolean,
): AuthenticationRequest.BodyContent =
    promptContent(
        description,
        record.lastUsedAt,
        record.rp,
        preferRpName,
        userLabel(record.user.name, record.user.displayName),
    )

private fun lastUsedLabel(lastUsedAt: Instant): String =
    DateUtils
        .getRelativeTimeSpanString(
            lastUsedAt.toEpochMilli(),
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
