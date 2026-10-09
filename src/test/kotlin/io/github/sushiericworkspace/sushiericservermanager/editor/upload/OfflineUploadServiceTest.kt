package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStoreKind
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResource
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfflineUploadServiceTest {
    @Test
    fun `選択データだけを正規化後にアップロードしてローカルを保持する`() {
        val root = createTempDirectory("offline-upload").toFile()
        try {
            val local = LocalEditorDataStore(root)
            local.save(EditorDataDescriptors.item, "one", validItem("one"))
            local.save(EditorDataDescriptors.item, "two", validItem("two"))
            val remote = InMemoryEditorDataStore("server")
            remote.save(EditorDataDescriptors.item, "one", validItem("one"))
            val service = OfflineUploadService(root, remote)

            val scan = assertIs<UploadScanResult.Success>(service.scan(UploadDataCategory.ITEM))
            assertEquals(
                UploadCandidateState.OVERWRITE,
                planUpload(scan, "").single { it.key.id == "one" }.state
            )

            val two = UploadKey(UploadDataCategory.ITEM, "two")
            val result = service.upload(setOf(two), emptySet(), "")

            assertEquals(listOf(two), result.succeeded)
            assertTrue(result.failed.isEmpty())
            assertIs<StoreResult.Success<MutableItemBaseData>>(
                remote.load(EditorDataDescriptors.item, "two")
            )
            assertIs<StoreResult.Success<MutableItemBaseData>>(
                local.load(EditorDataDescriptors.item, "two")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `上書き承認なしでは既存リモートを変更しない`() {
        val root = createTempDirectory("offline-upload-conflict").toFile()
        try {
            val local = LocalEditorDataStore(root)
            local.save(EditorDataDescriptors.item, "same", validItem("same", "Local"))
            val remote = InMemoryEditorDataStore("server")
            remote.save(EditorDataDescriptors.item, "same", validItem("same", "Remote"))
            val key = UploadKey(UploadDataCategory.ITEM, "same")

            val result = OfflineUploadService(root, remote).upload(setOf(key), emptySet(), "")

            assertEquals(
                StoreErrorCode.ALREADY_EXISTS,
                result.failed.single().error.code
            )
            assertEquals(
                "Remote",
                assertIs<StoreResult.Success<MutableItemBaseData>>(
                    remote.load(EditorDataDescriptors.item, "same")
                ).value.display.displayName
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `リモート一覧を取得できない場合は走査が失敗する`() {
        val root = createTempDirectory("offline-upload-unavailable").toFile()
        try {
            LocalEditorDataStore(root).save(
                EditorDataDescriptors.item,
                "one",
                validItem("one")
            )

            val scan = assertIs<UploadScanResult.Failure>(
                OfflineUploadService(root, ListUnavailableStore()).scan(UploadDataCategory.ITEM)
            )

            assertEquals(StoreErrorCode.STORE_UNAVAILABLE, scan.error.code)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validItem(id: String, name: String = "Sword"): MutableItemBaseData =
        MutableItemBaseData(id = id).apply { display.displayName = name }

    private class ListUnavailableStore : EditorDataStore {
        private val delegate = InMemoryEditorDataStore("unavailable")

        override val kind = EditorDataStoreKind.IN_MEMORY
        override val identity = "unavailable"
        override val isAvailable = false

        override fun <T : ManagedData<T, *>> list(
            descriptor: EditorDataDescriptor<T>
        ): StoreResult<List<StoreResource>> =
            StoreResult.Failure(StoreError(StoreErrorCode.STORE_UNAVAILABLE))

        override fun <T : ManagedData<T, *>> load(
            descriptor: EditorDataDescriptor<T>,
            id: String
        ): StoreResult<T> = delegate.load(descriptor, id)

        override fun readText(relativePath: String): StoreResult<String> =
            delegate.readText(relativePath)

        override fun writeText(relativePath: String, text: String): StoreResult<Unit> =
            delegate.writeText(relativePath, text)

        override fun <T : ManagedData<T, *>> save(
            descriptor: EditorDataDescriptor<T>,
            id: String,
            data: T
        ): StoreResult<Unit> = delegate.save(descriptor, id, data)

        override fun <T : ManagedData<T, *>> rename(
            descriptor: EditorDataDescriptor<T>,
            oldId: String,
            newName: String
        ): StoreResult<Unit> = delegate.rename(descriptor, oldId, newName)

        override fun <T : ManagedData<T, *>> delete(
            descriptor: EditorDataDescriptor<T>,
            id: String
        ): StoreResult<Unit> = delegate.delete(descriptor, id)

        override fun <T : ManagedData<T, *>> createDirectory(
            descriptor: EditorDataDescriptor<T>,
            directory: String
        ): StoreResult<Unit> = delegate.createDirectory(descriptor, directory)

        override fun <T : ManagedData<T, *>> deleteDirectory(
            descriptor: EditorDataDescriptor<T>,
            directory: String
        ): StoreResult<Unit> = delegate.deleteDirectory(descriptor, directory)

        override fun <T : ManagedData<T, *>> listDirectories(
            descriptor: EditorDataDescriptor<T>
        ): StoreResult<List<String>> = delegate.listDirectories(descriptor)

        override fun <T : ManagedData<T, *>> move(
            descriptor: EditorDataDescriptor<T>,
            id: String,
            targetDirectory: String
        ): StoreResult<String> = delegate.move(descriptor, id, targetDirectory)
    }

    @Test
    fun `宛先を指定するとローカルのディレクトリを置き換えてアップロードする`() {
        val root = createTempDirectory("offline-upload-directory").toFile()
        try {
            val id = "combat.sword.test_sword"
            val local = LocalEditorDataStore(root)
            local.save(EditorDataDescriptors.item, id, validItem(id))
            val remote = InMemoryEditorDataStore("server")
            val service = OfflineUploadService(root, remote)

            val scan = assertIs<UploadScanResult.Success>(service.scan(UploadDataCategory.ITEM))
            val key = UploadKey(UploadDataCategory.ITEM, id)
            val candidate = planUpload(scan, "event.weapons").single()
            assertEquals(UploadCandidateState.NEW, candidate.state)
            assertEquals("event.weapons.test_sword", candidate.targetId)

            val result = service.upload(setOf(key), emptySet(), "event.weapons")

            assertEquals(listOf(key), result.succeeded)
            assertEquals(
                "event.weapons.test_sword",
                assertIs<StoreResult.Success<MutableItemBaseData>>(
                    remote.load(EditorDataDescriptors.item, "event.weapons.test_sword")
                ).value.id
            )
            assertIs<StoreResult.Failure>(remote.load(EditorDataDescriptors.item, id))
            assertIs<StoreResult.Success<MutableItemBaseData>>(local.load(EditorDataDescriptors.item, id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `宛先を空にするとルート直下の名前でアップロードする`() {
        val root = createTempDirectory("offline-upload-root").toFile()
        try {
            val id = "combat.sword.test_sword"
            LocalEditorDataStore(root).save(EditorDataDescriptors.item, id, validItem(id))
            val remote = InMemoryEditorDataStore("server")

            val result = OfflineUploadService(root, remote)
                .upload(setOf(UploadKey(UploadDataCategory.ITEM, id)), emptySet(), "")

            assertTrue(result.failed.isEmpty())
            assertIs<StoreResult.Success<MutableItemBaseData>>(remote.load(EditorDataDescriptors.item, "test_sword"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `宛先の指定が不正な場合は何もアップロードしない`() {
        val root = createTempDirectory("offline-upload-invalid").toFile()
        try {
            LocalEditorDataStore(root).save(EditorDataDescriptors.item, "one", validItem("one"))
            val remote = InMemoryEditorDataStore("server")
            val key = UploadKey(UploadDataCategory.ITEM, "one")

            val result = OfflineUploadService(root, remote).upload(setOf(key), emptySet(), "Bad..dir")

            assertTrue(result.succeeded.isEmpty())
            assertEquals(StoreErrorCode.INVALID_ID, result.failed.single().error.code)
            assertIs<StoreResult.Failure>(remote.load(EditorDataDescriptors.item, "one"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `宛先を含めたIDが既存の場合は上書き承認が必要になる`() {
        val root = createTempDirectory("offline-upload-target-conflict").toFile()
        try {
            LocalEditorDataStore(root).save(EditorDataDescriptors.item, "one", validItem("one", "Local"))
            val remote = InMemoryEditorDataStore("server")
            remote.save(EditorDataDescriptors.item, "event.one", validItem("event.one", "Remote"))
            val key = UploadKey(UploadDataCategory.ITEM, "one")
            val service = OfflineUploadService(root, remote)

            val refused = service.upload(setOf(key), emptySet(), "event")
            assertEquals(StoreErrorCode.ALREADY_EXISTS, refused.failed.single().error.code)

            val approved = service.upload(setOf(key), setOf(key), "event")
            assertEquals(listOf(key), approved.succeeded)
        } finally {
            root.deleteRecursively()
        }
    }
}
