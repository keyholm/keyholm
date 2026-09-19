package app.keyholm.webauthn

import android.content.Context
import app.keyholm.util.logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

private const val RELATION_GET_LOGIN_CREDS = "delegate_permission/common.get_login_creds"
private const val NAMESPACE_ANDROID_APP = "android_app"
private const val ASSETS_FILE = "community_assetlinks.json"

data class AssetLinkStatement(
    val packageName: PackageName,
    val fingerprints: Set<CertFingerprint>,
)

@Serializable
private data class RawTarget(
    val namespace: String = "",
    @SerialName("package_name") val packageName: String = "",
    @SerialName("sha256_cert_fingerprints") val fingerprints: List<String> = emptyList(),
)

@Serializable
private data class RawStatement(
    val relation: List<String> = emptyList(),
    val target: RawTarget = RawTarget(),
)

private val json = Json { ignoreUnknownKeys = true }

private fun List<RawStatement>.toAssetLinkStatements(): List<AssetLinkStatement> =
    mapNotNull { statement ->
        if (RELATION_GET_LOGIN_CREDS !in statement.relation) return@mapNotNull null
        if (statement.target.namespace != NAMESPACE_ANDROID_APP) return@mapNotNull null
        val packageName = statement.target.packageName.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val fingerprints = CertFingerprint.of(statement.target.fingerprints.filter { it.isNotEmpty() })
        if (fingerprints.isEmpty()) return@mapNotNull null
        AssetLinkStatement(PackageName(packageName), fingerprints)
    }

internal fun parseAssetLinkStatements(raw: String): List<AssetLinkStatement> =
    json.decodeFromString<List<RawStatement>>(raw).toAssetLinkStatements()

internal fun parseCommunityAssetLinks(raw: String): Map<RpId, List<AssetLinkStatement>> =
    json
        .decodeFromString<Map<String, List<RawStatement>>>(raw)
        .entries
        .associate { (domain, statements) -> RpId(domain) to statements.toAssetLinkStatements() }

object CommunityAssetLinks {
    private val log = logger()
    private var parsed: Map<RpId, List<AssetLinkStatement>>? = null

    fun load(context: Context): Map<RpId, List<AssetLinkStatement>> = parsed ?: read(context.applicationContext).also { parsed = it }

    private fun read(context: Context): Map<RpId, List<AssetLinkStatement>> =
        try {
            context.assets.open(ASSETS_FILE).use {
                parseCommunityAssetLinks(it.readBytes().toString(Charsets.UTF_8))
            }
        } catch (e: IOException) {
            log.e(e) { "couldn't read $ASSETS_FILE, not trusting anyone from community list" }
            emptyMap()
        } catch (e: SerializationException) {
            log.e(e) { "couldn't parse $ASSETS_FILE, not trusting anyone from community list" }
            emptyMap()
        }
}
