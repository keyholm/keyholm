package app.keyholm.iconpack

import android.content.Context
import android.net.Uri
import app.keyholm.util.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipFile

private const val PACK_DEFINITION = "pack.json"

private const val PACK_FILE = "icons.zip"
private const val INCOMING_FILE = "icons.zip.part"

private const val MAX_DEFINITION_BYTES = 4 * 1024 * 1024

private val json = Json { ignoreUnknownKeys = true }

sealed interface IconPackImport {
    data class Installed(
        val pack: IconPack,
    ) : IconPackImport

    data object Invalid : IconPackImport

    data object InstallFailed : IconPackImport
}

sealed interface IconPackLoad {
    data object None : IconPackLoad

    data class Loaded(
        val pack: IconPack,
    ) : IconPackLoad

    data object Damaged : IconPackLoad
}

private sealed interface SourceCopy {
    data object Copied : SourceCopy

    data class SourceFailed(
        val error: Throwable,
    ) : SourceCopy

    data class WriteFailed(
        val error: Throwable,
    ) : SourceCopy
}

class IconPackStorage(
    context: Context,
    private val dispatcher: CoroutineDispatcher,
    private val renderDispatcher: CoroutineDispatcher,
) {
    private val log = logger()
    private val appContext = context.applicationContext
    private val packFile = File(appContext.filesDir, PACK_FILE)
    private val publicSuffixes = PublicSuffixList(appContext, dispatcher)

    suspend fun load(): IconPackLoad =
        withContext(dispatcher) {
            if (!packFile.isFile) return@withContext IconPackLoad.None
            val zip = openZip(packFile) ?: return@withContext IconPackLoad.Damaged
            val definition = readDefinition(zip)
            if (definition == null) {
                zip.close()
                return@withContext IconPackLoad.Damaged
            }
            IconPackLoad.Loaded(IconPack(definition, zip, publicSuffixes, dispatcher, renderDispatcher))
        }

    suspend fun import(uri: Uri): IconPackImport =
        withContext(dispatcher) {
            val incoming = File(appContext.filesDir, INCOMING_FILE)
            try {
                install(uri, incoming)
            } finally {
                incoming.delete()
            }
        }

    suspend fun remove(): Result<Unit> =
        withContext(dispatcher) {
            runCatching { Files.delete(packFile.toPath()) }.onFailure { log.e(it) { "couldn't delete ${packFile.name}" } }
        }

    private fun install(
        uri: Uri,
        incoming: File,
    ): IconPackImport {
        val source =
            runCatching { appContext.contentResolver.openInputStream(uri) }
                .onFailure { log.e(it) { "couldn't open $uri" } }
                .onSuccess { if (it == null) log.e { "no stream for $uri" } }
                .getOrNull() ?: return IconPackImport.Invalid
        return when (val copy = source.use { copy(it, incoming) }) {
            SourceCopy.Copied -> {
                installCopy(incoming)
            }

            is SourceCopy.SourceFailed -> {
                log.e(copy.error) { "couldn't read $uri" }
                IconPackImport.Invalid
            }

            is SourceCopy.WriteFailed -> {
                log.e(copy.error) { "couldn't write ${incoming.name}" }
                IconPackImport.InstallFailed
            }
        }
    }

    private fun installCopy(incoming: File): IconPackImport {
        val zip = openZip(incoming) ?: return IconPackImport.Invalid
        val definition = readDefinition(zip)
        if (definition == null) {
            zip.close()
            return IconPackImport.Invalid
        }
        if (!incoming.renameTo(packFile)) {
            zip.close()
            return IconPackImport.InstallFailed
        }
        return IconPackImport.Installed(IconPack(definition, zip, publicSuffixes, dispatcher, renderDispatcher))
    }

    private fun copy(
        input: InputStream,
        incoming: File,
    ): SourceCopy =
        runCatching { incoming.outputStream() }.fold(
            onSuccess = { output -> output.use { transfer(input, it, ByteArray(DEFAULT_BUFFER_SIZE)) } },
            onFailure = { SourceCopy.WriteFailed(it) },
        )

    private tailrec fun transfer(
        input: InputStream,
        output: OutputStream,
        buffer: ByteArray,
    ): SourceCopy {
        val count = runCatching { input.read(buffer) }.getOrElse { return SourceCopy.SourceFailed(it) }
        if (count < 0) return SourceCopy.Copied
        runCatching { output.write(buffer, 0, count) }.onFailure { return SourceCopy.WriteFailed(it) }
        return transfer(input, output, buffer)
    }

    private fun openZip(file: File): ZipFile? =
        runCatching { ZipFile(file) }.onFailure { log.e(it) { "couldn't open ${file.name}" } }.getOrNull()

    private fun readDefinition(zip: ZipFile): IconPackDefinition? {
        val bytes = definitionBytes(zip) ?: return null
        return runCatching { json.decodeFromString<IconPackDefinition>(bytes.toString(Charsets.UTF_8)) }
            .onFailure { log.e(it) { "couldn't parse $PACK_DEFINITION in ${zip.name}" } }
            .getOrNull()
    }

    private fun definitionBytes(zip: ZipFile): ByteArray? =
        runCatching {
            val entry = zip.getEntry(PACK_DEFINITION)
            if (entry == null) log.e { "no $PACK_DEFINITION in ${zip.name}" }
            entry?.let { zip.getInputStream(it).use { input -> input.readNBytes(MAX_DEFINITION_BYTES) } }
        }.onFailure { log.e(it) { "couldn't read $PACK_DEFINITION in ${zip.name}" } }.getOrNull()
}
