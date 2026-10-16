package io.github.sushiericworkspace.sushiericservermanager.editor.offline

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface ManifestReadResult {
    data object Missing : ManifestReadResult
    data class Success(val manifest: OfflineWorkspaceManifest) : ManifestReadResult
    data class Invalid(val cause: Throwable?) : ManifestReadResult
    data class FutureVersion(val version: Int) : ManifestReadResult
}

class OfflineManifestRepository(
    private val workspaceRoot: File,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val manifestFile: File
        get() = workspaceRoot.resolve(".editor").resolve("manifest.json")

    fun read(): ManifestReadResult {
        if (!manifestFile.isFile) return ManifestReadResult.Missing
        return try {
            val manifest = json.decodeFromString<OfflineWorkspaceManifest>(manifestFile.readText())
            when {
                manifest.workspaceFormatVersion > OfflineFormatVersion.CURRENT_WORKSPACE_FORMAT_VERSION ->
                    ManifestReadResult.FutureVersion(manifest.workspaceFormatVersion)
                manifest.dataFormatVersion > OfflineFormatVersion.CURRENT_DATA_FORMAT_VERSION ->
                    ManifestReadResult.FutureVersion(manifest.dataFormatVersion)
                else -> ManifestReadResult.Success(manifest)
            }
        } catch (e: SerializationException) {
            ManifestReadResult.Invalid(e)
        } catch (e: Exception) {
            logger.error("オフラインマニフェストの読み込みに失敗しました", e)
            ManifestReadResult.Invalid(e)
        }
    }

    fun write(manifest: OfflineWorkspaceManifest): Boolean {
        val directory = manifestFile.parentFile
        if (!directory.exists() && !directory.mkdirs()) return false
        val temporary = try {
            Files.createTempFile(directory.toPath(), ".manifest-", ".tmp").toFile()
        } catch (e: Exception) {
            logger.error("マニフェスト用一時ファイルを作成できませんでした", e)
            return false
        }

        return try {
            temporary.writeText(json.encodeToString(manifest))
            try {
                Files.move(
                    temporary.toPath(),
                    manifestFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    manifestFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            true
        } catch (e: Exception) {
            logger.error("オフラインマニフェストの保存に失敗しました", e)
            false
        } finally {
            temporary.delete()
        }
    }
}
