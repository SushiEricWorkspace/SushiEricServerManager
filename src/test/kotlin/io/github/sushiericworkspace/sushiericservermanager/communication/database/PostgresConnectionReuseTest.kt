package io.github.sushiericworkspace.sushiericservermanager.communication.database

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.config.*
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PostgresConnectionReuseTest {
    @TempDir lateinit var root: Path
    private val product = HostProductRead("tab.apple", HostDefinition(UUID.randomUUID().toString(), 100, 50),
        "a".repeat(64), HostCurrentStock(12, 7))

    @Test fun `shopのない先行profileと相対shopを自動補完しない`() {
        val reference = postgresReference()
        val fields = Json.parseToJsonElement(Json.encodeToString(PostgresHostReference.serializer(), reference)).jsonObject
        val missing = JsonObject(fields.filterKeys { it != "shop" })
        assertFails { Json.decodeFromString(PostgresHostReference.serializer(), missing.toString()) }
        assertFails { reference.copy(shop = reference.shop.copy(root = "relative/shop_data")).validate() }
        assertFails { reference.copy(shop = reference.shop.copy(identity = "")).validate() }
    }

    @Test fun `SSHの通常と選択経路で商品と履歴が一つのHを共有する`() {
        for (selected in listOf(false, true)) {
            val reference = postgresReference()
            var sessionId: String? = null
            val pageEntered = CountDownLatch(1)
            val pageRelease = CountDownLatch(1)
            val channel = HostChannelFixture(reference) { command ->
                if (command is PostgresWriterCommand.Open) sessionId = command.sessionUuid
                if (command is PostgresWriterCommand.Read && command.request.action is HostAdapterAction.HistoryPage) {
                    pageEntered.countDown()
                    check(pageRelease.await(5, TimeUnit.SECONDS))
                }
                if (command is PostgresWriterCommand.Read && command.request.action is HostAdapterAction.Products)
                    PostgresWriterResponse(status = PostgresWriterStatus.READ, sessionUuid = sessionId,
                        requestId = command.request.requestId, runSha256 = reference.status.sha256,
                        stateRevision = reference.status.state.stateRevision,
                        result = HostAdapterResult.Products(listOf(product)))
                else null
            }
            val client = mock(SSHClient::class.java)
            val ssh = remote(reference)
            `when`(ssh.sshClient).thenReturn(client)
            `when`(client.isConnected).thenReturn(true)
            stubSshRunState(client, reference)
            val session = mock(Session::class.java)
            val command = mock(Session.Command::class.java)
            `when`(client.startSession()).thenReturn(session)
            `when`(session.exec(anyString())).thenReturn(command)
            `when`(command.outputStream).thenReturn(channel.output)
            `when`(command.inputStream).thenReturn(channel.input)
            `when`(command.errorStream).thenReturn(channel.error)
            `when`(command.exitStatus).thenReturn(0)
            val configured = if (selected) ShopEditorStore(ShopSaveSession(
                SelectedShopTransport(ssh, mock(ServerManagementClient::class.java)),
                root.resolve("selected/request.json"))) else null
            val store = RemoteEditorDataStore(ssh, configured)
            val pool = Executors.newFixedThreadPool(2)
            try {
                assertIs<StoreResult.Success<*>>(store.load(EditorDataDescriptors.shop, product.id))
                val search = requireNotNull(store.historySource).open()
                val page = pool.submit<List<HostHistoryRecord>> { search.next() }
                assertTrue(pageEntered.await(5, TimeUnit.SECONDS))
                val submitted = CountDownLatch(1)
                val during = pool.submit<StoreResult<*>> {
                    submitted.countDown()
                    store.load(EditorDataDescriptors.shop, product.id)
                }
                assertTrue(submitted.await(5, TimeUnit.SECONDS))
                assertFalse(during.isDone)
                pageRelease.countDown()
                assertTrue(page.get(5, TimeUnit.SECONDS).isEmpty())
                assertIs<StoreResult.Success<*>>(during.get(5, TimeUnit.SECONDS))
                search.close()
                assertFalse(channel.eof)
                assertIs<StoreResult.Success<*>>(store.load(EditorDataDescriptors.shop, product.id))
                assertEquals(1, channel.commands.count { it is PostgresWriterCommand.Open })
                verify(client, times(1)).startSession()
                verify(ssh, never()).exists(anyString())
            } finally {
                pageRelease.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
                store.closeDatabase()
            }
            assertTrue(channel.eof)
            assertEquals(1, channel.commands.count { it is PostgresWriterCommand.Close })
        }
    }

    @Test fun `SSH一覧とpath読取はprofile外の承認shopだけを参照する`() {
        val reference = postgresReference()
        val ssh = remote(reference)
        fun entry(name: String, type: FileMode.Type) = mock(RemoteResourceInfo::class.java).also {
            val attributes = mock(FileAttributes::class.java)
            `when`(attributes.type).thenReturn(type)
            `when`(it.name).thenReturn(name)
            `when`(it.attributes).thenReturn(attributes)
        }
        val tab = entry("tab", FileMode.Type.DIRECTORY)
        val apple = entry("apple.yml", FileMode.Type.REGULAR)
        `when`(ssh.listFilesOrThrow(reference.shop.root)).thenReturn(listOf(tab))
        `when`(ssh.listFilesOrThrow(reference.shop.root + "/tab"))
            .thenReturn(listOf(apple))
        val store = RemoteEditorDataStore(ssh)
        val listed = assertIs<StoreResult.Success<List<StoreResource>>>(store.list(EditorDataDescriptors.shop)).value
        assertEquals(listOf(product.id), listed.map { it.id })
        assertEquals(reference.shop.root + "/tab/apple.yml", listed.single().location)
        assertEquals(listOf("tab"), assertIs<StoreResult.Success<List<String>>>(
            store.listDirectories(EditorDataDescriptors.shop)).value)
        assertIs<StoreResult.Success<*>>(store.listPath("shop_data/tab"))
        verify(ssh, never()).listFilesOrThrow("/server/sushieric/shop_data")
    }

    @Test fun `旧世代UNKNOWNと旧形式の保存待ちはdeleteとsaveでFailureになり元bytesを保全する`() {
        val reference = postgresReference()
        val binding = reference.status.state.binding
        for (legacy in listOf(false, true)) {
            val file = root.resolve("pending-$legacy/request.json")
            val old = ShopSaveSession(object : ShopTransport {
                override fun worldUuid() = binding.worldUuid
                override fun databaseBinding() = binding
                override fun execute(request: HostAdapterRequest) = HostAdapterResponse(
                    requestId = request.requestId, status = HostAdapterStatus.UNKNOWN)
            }, file)
            old.execute(HostAdapterAction.Edit(listOf(ShopSavePlan.delete(product))))
            if (legacy) Files.write(file, HostAdapterCodec.encodeRequest(requireNotNull(old.pending())))
            val bytes = Files.readAllBytes(file)
            var sends = 0
            val otherBinding = binding.copy(generation = UUID.randomUUID().toString())
            val products = ShopEditorStore(ShopSaveSession(object : ShopTransport {
                override fun worldUuid() = binding.worldUuid
                override fun databaseBinding() = otherBinding
                override fun execute(request: HostAdapterRequest): HostAdapterResponse { sends++; error("送信しません") }
            }, file))
            val store = RemoteEditorDataStore(remote(reference), products)
            for (operation in listOf<() -> StoreResult<Unit>>(
                { products.delete(product) },
                { products.save(ShopEditorStore.mutable(product), product) },
                { store.save(EditorDataDescriptors.shop, product.id, ShopEditorStore.mutable(product)) })) {
                val failure = assertIs<StoreResult.Failure>(operation())
                assertEquals(StoreErrorCode.PENDING_REVIEW, failure.error.code)
                assertContentEquals(bytes, Files.readAllBytes(file))
                assertEquals(0, sends)
            }
        }
    }

    private fun remote(reference: PostgresHostReference) = mock(SshManager::class.java).also {
        `when`(it.currentProfile).thenReturn(ServerProfile("test", "fixture.invalid", 22, "fixture", "/server", "",
            databaseHistory = DatabaseHistoryHost(reference.status.state.binding.worldUuid, postgres = reference)))
        `when`(it.isSftpActive).thenReturn(true)
        `when`(it.compatibility).thenReturn(ManagerCompatibility.Compatible("0.0.0"))
    }
}
