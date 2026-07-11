package app.keyholm.settings

import androidx.datastore.preferences.core.MutablePreferences
import app.keyholm.webauthn.AlgorithmFamily
import app.keyholm.webauthn.AlgorithmNegotiation
import app.keyholm.webauthn.AlgorithmPreference
import app.keyholm.webauthn.MlDsaSupport

class AlgorithmSettingsController(
    private val launchEdit: (suspend (MutablePreferences) -> Unit) -> Unit,
) {
    fun setEnabled(
        family: AlgorithmFamily,
        enabled: Boolean,
    ) = launchEdit { prefs ->
        val updated = if (enabled) storedNames(prefs) + family.name else storedNames(prefs) - family.name
        if (offersAnything(AlgorithmFamily.fromStrings(updated), storedSupport(prefs))) {
            prefs[SettingsKeys.enabledFamilies] = updated
            resetUnreachablePreferences(prefs)
        }
    }

    fun setMlDsaSupport(support: MlDsaSupport) =
        launchEdit { prefs ->
            if (offersAnything(AlgorithmFamily.fromStrings(storedNames(prefs)), support)) {
                prefs[SettingsKeys.mlDsaSupport] = encodeMlDsaSupport(support)
                resetUnreachablePreferences(prefs)
            }
        }

    fun setPreferred(preference: AlgorithmPreference) =
        launchEdit {
            it[SettingsKeys.preferredAlgorithm] =
                encodeAlgorithmPreference(preference)
        }

    fun setFallback(preference: AlgorithmPreference) =
        launchEdit {
            it[SettingsKeys.fallbackAlgorithm] =
                encodeAlgorithmPreference(preference)
        }

    private fun storedNames(prefs: MutablePreferences): Set<String> =
        prefs[SettingsKeys.enabledFamilies] ?: AlgorithmFamily.toStrings(DEFAULT_ENABLED_FAMILIES)

    private fun storedSupport(prefs: MutablePreferences): MlDsaSupport = decodeMlDsaSupport(prefs[SettingsKeys.mlDsaSupport])

    private fun offersAnything(
        enabled: Set<AlgorithmFamily>,
        support: MlDsaSupport,
    ): Boolean = AlgorithmNegotiation.allowedAlgorithms(enabled, support).isNotEmpty()

    // Revert to Always Ask if the config isn't valid anymore
    private fun resetUnreachablePreferences(prefs: MutablePreferences) {
        val allowed =
            AlgorithmNegotiation.allowedAlgorithms(
                AlgorithmFamily.fromStrings(storedNames(prefs)),
                storedSupport(prefs),
            )
        listOf(SettingsKeys.preferredAlgorithm, SettingsKeys.fallbackAlgorithm)
            .filter { key ->
                val pinned = (decodeAlgorithmPreference(prefs[key]) as? AlgorithmPreference.Prefer)?.family
                pinned != null && allowed.none { it.family == pinned }
            }.forEach { prefs[it] = encodeAlgorithmPreference(AlgorithmPreference.AlwaysAsk) }
    }
}
