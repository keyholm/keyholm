package app.keyholm.iconpack

import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import app.keyholm.util.logger
import app.keyholm.webauthn.RpId
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import java.util.zip.ZipFile

@Serializable
internal data class IconPackDefinition(
    val name: String,
    val version: Int,
    val icons: List<IconDefinition>,
)

@Serializable
internal data class IconDefinition(
    val filename: String,
    val name: String? = null,
    val issuer: List<String> = emptyList(),
)

private const val SVG_EXTENSION = ".svg"

private const val ICON_CACHE_ENTRIES = 64

private const val MAX_ICON_BYTES = 1024 * 1024

private const val MIN_PARTIAL_MATCH = 4

private fun normalize(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

/** Longest icon name that matches the registrable domain or is equal to its first label */
internal fun matchIconEntry(
    byName: Map<String, String>,
    registrableDomain: String,
): String? {
    val domain = normalize(registrableDomain)
    val label = normalize(registrableDomain.substringBefore('.'))
    return ((domain.length downTo MIN_PARTIAL_MATCH) + label.length).firstNotNullOfOrNull { byName[domain.take(it)] }
}

internal fun iconIndex(definition: IconPackDefinition): Map<String, String> =
    buildMap {
        definition.icons.filter { it.filename.endsWith(SVG_EXTENSION, ignoreCase = true) }.forEach { icon ->
            (listOfNotNull(icon.name) + icon.issuer).forEach { putIfAbsent(normalize(it), icon.filename) }
        }
    }

sealed interface RenderedIcon {
    data class Drawn(
        val bitmap: ImageBitmap,
    ) : RenderedIcon

    data object NotInPack : RenderedIcon
}

private fun cacheKey(
    rpId: RpId,
    sizePx: Int,
) = "${rpId.value}@$sizePx"

@Stable
class IconPack internal constructor(
    definition: IconPackDefinition,
    private val zip: ZipFile,
    private val publicSuffixes: PublicSuffixList,
    private val ioDispatcher: CoroutineDispatcher,
    private val renderDispatcher: CoroutineDispatcher,
) : AutoCloseable {
    val name = definition.name
    val version = definition.version
    private val byName = iconIndex(definition)
    private val log = logger()
    private val cache = LruCache<String, RenderedIcon>(ICON_CACHE_ENTRIES)

    override fun close() = zip.close()

    fun cachedIcon(
        rpId: RpId,
        sizePx: Int,
    ): RenderedIcon? = cache.get(cacheKey(rpId, sizePx))

    suspend fun icon(
        rpId: RpId,
        sizePx: Int,
    ): RenderedIcon {
        val key = cacheKey(rpId, sizePx)
        return cache.get(key) ?: load(rpId, sizePx).also { cache.put(key, it) }
    }

    private suspend fun load(
        rpId: RpId,
        sizePx: Int,
    ): RenderedIcon {
        val registrableDomain = publicSuffixes.getPublicSuffixPlusOne(rpId.value).await() ?: return RenderedIcon.NotInPack
        val entry = matchIconEntry(byName, registrableDomain) ?: return RenderedIcon.NotInPack
        val bytes = withContext(ioDispatcher) { readIcon(entry) } ?: return RenderedIcon.NotInPack
        return withContext(renderDispatcher) { render(entry, bytes, sizePx) }
    }

    private fun render(
        entry: String,
        bytes: ByteArray,
        sizePx: Int,
    ): RenderedIcon {
        val svg =
            runCatching { SVG.getFromInputStream(bytes.inputStream()) }
                .onFailure { log.e(it) { "couldn't parse $entry" } }
                .getOrNull()
        if (svg == null || !svg.fitTo(sizePx)) return RenderedIcon.NotInPack
        val bitmap = createBitmap(sizePx, sizePx)
        svg.renderToCanvas(Canvas(bitmap))
        return RenderedIcon.Drawn(bitmap.asImageBitmap())
    }

    private fun readIcon(entry: String): ByteArray? =
        runCatching {
            val icon = zip.getEntry(entry) ?: return null
            if (icon.size > MAX_ICON_BYTES) {
                log.w { "skipping $entry over $MAX_ICON_BYTES bytes" }
                return null
            }
            zip.getInputStream(icon).use { it.readNBytes(MAX_ICON_BYTES) }
        }.onFailure { log.e(it) { "couldn't read $entry" } }.getOrNull()
}

private fun SVG.fitTo(sizePx: Int): Boolean {
    if (documentViewBox == null) {
        if (documentWidth <= 0f || documentHeight <= 0f) return false
        setDocumentViewBox(0f, 0f, documentWidth, documentHeight)
    }
    setDocumentWidth(sizePx.toFloat())
    setDocumentHeight(sizePx.toFloat())
    return true
}
