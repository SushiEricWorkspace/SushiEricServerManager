package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.config.OS
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStoreKind
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class LocalDocumentPathTest {
    @TempDir lateinit var root: File

    private val source get() = EditorDocumentSource.Server("path-probe-"+root.name, "declared/root")

    private fun store() = object : EditorDataStore by InMemoryEditorDataStore() {
        override val documentSource = source
        override val kind = EditorDataStoreKind.REMOTE
    }

    @Test
    fun customBackupRootContainsItsDocumentsAndCodec() {
        val backup = root.resolve("arbitrary-backup-name")
        val service = EditorDataService(store(), backup)
        try {
            assertTrue(service.ores.saveLocalDocument("one", service.ores.createDefault("one"), emptyMap()) is StoreResult.Success)
            assertTrue(backup.resolve("documents").resolve(source.directory).resolve("current").isFile)
            assertTrue(backup.resolve("document-codec").isDirectory)
            assertFalse(root.resolve("documents").exists())
        } finally { service.closeIo() }
    }

    @Test
    fun defaultRootIsInsideTheTestJvmAppHome() {
        val testRoot = Path.of(checkNotNull(System.getProperty("sushieric.test.configRoot"))).toAbsolutePath().normalize()
        check(Path.of(OS.dataConfigBase).toAbsolutePath().normalize().startsWith(testRoot))
        val service = EditorDataService(store())
        try {
            assertTrue(service.ores.saveLocalDocument("one", service.ores.createDefault("one"), emptyMap()) is StoreResult.Success)
            assertTrue(FilePath.AUTOSAVE_DIR.toFile().resolve("documents").resolve(source.directory).resolve("current").isFile)
            assertTrue(FilePath.AUTOSAVE_DIR.toFile().toPath().toAbsolutePath().normalize().startsWith(testRoot))
        } finally { service.closeIo() }
    }

    @Test
    fun explicitSharedLocalSourceDoesNotUseTheManagedBindingWorkspace() {
        val appAutosave = root.resolve("app/autosave")
        val documents = appAutosave.resolve("documents")
        val binding = root.resolve("binding/autosave")
        val sharedOffline = root.resolve("app/offline")
        val service = EditorDataService(store(), binding, documents, sharedOffline)
        try {
            assertTrue(service.ores.saveLocalDocument("same", service.ores.createDefault("same"), emptyMap()) is StoreResult.Success)
            val local = service.localService
            assertTrue(local.ores.saveLocalDocument("same", local.ores.createDefault("same"), emptyMap()) is StoreResult.Success)
            assertTrue(documents.resolve(source.directory).resolve("current").isFile)
            assertTrue(documents.resolve(EditorDocumentSource.Local.directory).resolve("current").isFile)
            assertTrue(appAutosave.resolve("document-codec").isDirectory)
            assertFalse(binding.exists())
            assertFalse(sharedOffline.exists())
        } finally { service.closeIo() }
    }
}
