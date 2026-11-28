package io.github.sushiericworkspace.sushiericservermanager.communication.database

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericdatamodel.database.run.*
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.ShopProductStock
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.*
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresPortIntegrationTest {
    private val work = Path.of(System.getProperty("postgresPortWorkRoot")).toAbsolutePath().normalize()
    private lateinit var run: Path
    private lateinit var pg: EmbeddedPostgres
    private var port = 0
    private var pid = 0L
    private val transports = mutableListOf<ShopTransport>()

    @BeforeAll fun start() {
        require(work in setOf(Path.of("F:/codex-tmp/pg-manager-334"), Path.of("F:/codex-tmp/pg-manager-334-mgmt")))
        run = Files.createTempDirectory(work, "pg-integration-").toRealPath()
        val forbidden = setOf(26335, 26336, 26345, 26346, 26350, 26351) + (25770..25779)
        port = generateSequence {
            ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.first { it !in forbidden }
        pg = EmbeddedPostgres.builder().setPort(port).setDataDirectory(run.resolve("pgdata"))
            .setOverrideWorkingDirectory(work.resolve("native").toFile()).setCleanDataDirectory(false)
            .setPGStartupWait(Duration.ofSeconds(45))
            .setServerConfig("listen_addresses", "127.0.0.1")
            .setServerConfig("fsync", "on").setServerConfig("synchronous_commit", "on")
            .setServerConfig("shared_buffers", "16MB").setServerConfig("max_connections", "12")
            .setServerConfig("password_encryption", "scram-sha-256")
            .setServerConfig("log_min_error_statement", "panic")
            .setServerConfig("log_parameter_max_length_on_error", "0")
            .setLocaleConfig("locale", "C").setLocaleConfig("encoding", "UTF-8")
            .setOutputRedirector(ProcessBuilder.Redirect.appendTo(run.resolve("cluster.log").toFile()))
            .setErrorRedirector(ProcessBuilder.Redirect.appendTo(run.resolve("cluster.log").toFile())).start()
        pid = Files.readAllLines(run.resolve("pgdata/postmaster.pid")).first().toLong()
        source("postgres").connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SHOW server_version_num").use { r -> assertTrue(r.next()); assertEquals("180007", r.getString(1)) }
            }
        }
    }

    @AfterAll fun stop() {
        if (!::pg.isInitialized) return
        try { transports.forEach { it.close() } } finally { pg.close() }
        ProcessHandle.of(pid).ifPresent { it.onExit().get(45, TimeUnit.SECONDS); assertFalse(it.isAlive) }
        assertFalse(Files.exists(run.resolve("pgdata/postmaster.pid")))
        ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).use { assertTrue(it.isBound) }
        Files.writeString(run.resolve("stopped.txt"), "pid=$pid;port=$port;normal-stop-confirmed\n")
    }

    private fun source(database: String) = PGSimpleDataSource().apply {
        setServerNames(arrayOf("127.0.0.1")); setPortNumbers(intArrayOf(port))
        databaseName = database; user = "postgres"; connectTimeout = 5; socketTimeout = 15; sslMode = "disable"
    }
    private fun sql(database: String, text: String) {
        source(database).connection.use { c -> c.createStatement().use { it.execute(text) } }
        source("postgres").connection.use { c ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val count = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=? AND pid<>pg_backend_pid()").use {
                    it.setString(1, database); it.executeQuery().use { r -> r.next(); r.getInt(1) }
                }
                if (count == 0) break
                check(System.nanoTime() < deadline); Thread.sleep(20)
            }
        }
    }
    private fun protect(path: Path) {
        val process = ProcessBuilder(Path.of(System.getenv("SystemRoot"), "System32/icacls.exe").toString(),
            path.toString(), "/inheritance:r", "/q").redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0)
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        view.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.owner)
            .setPermissions(AclEntryPermission.entries.toSet()).build())
    }

    @Test fun `固定HostZIPでLocal連続編集と照合とPG容量失敗を検証する`() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val database = "manager_$suffix"
        val owner = "owner_$suffix"
        val login = "login_$suffix"
        val world = Files.createDirectory(run.resolve("world")).toRealPath()
        val ownership = Files.createDirectory(run.resolve("ownership")).toRealPath()
        val vault = Files.createDirectory(run.resolve("vault"))
        val secret = Files.createFile(vault.resolve("password.secret"))
        listOf(vault, secret).forEach(::protect)
        Files.writeString(secret, UUID.randomUUID().toString())
        try {
            sql("postgres", "CREATE ROLE $owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS")
            source("postgres").connection.use { c ->
                val command = c.prepareStatement("SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',?,?)").use {
                    it.setString(1, login); it.setString(2, Files.readString(secret))
                    it.executeQuery().use { r -> r.next(); r.getString(1) }
                }
                c.createStatement().use { it.execute(command) }
            }
            sql("postgres", "CREATE DATABASE $database OWNER $owner TEMPLATE template0")
            sql("postgres", "GRANT $owner TO $login WITH INHERIT FALSE")
            sql("postgres", "REVOKE ALL ON DATABASE $database FROM PUBLIC")
            sql("postgres", "GRANT CONNECT ON DATABASE $database TO $login")
            val hba = run.resolve("pgdata/pg_hba.conf")
            Files.writeString(hba, "host $database $login 127.0.0.1/32 scram-sha-256\n" + Files.readString(hba))
            sql("postgres", "SELECT pg_reload_conf()")
            val settings = PostgresNewGameSettings(connectionReference = "manager_fixture",
                deploymentUuid = UUID.randomUUID().toString(), databaseUuid = UUID.randomUUID().toString(),
                worldUuid = UUID.randomUUID().toString(), generation = UUID.randomUUID().toString(),
                worldRoot = world.toString(), ownershipDirectory = ownership.toString(),
                operationId = UUID.randomUUID().toString(), requestSha256 = "a".repeat(64),
                capacityConfirmationSha256 = "b".repeat(64), ownerRole = owner,
                connection = mapOf("host" to "127.0.0.1", "port" to port.toString(), "database" to database,
                    "role" to login, "sslMode" to "disable", "connectTimeoutSeconds" to "5", "socketTimeoutSeconds" to "15"),
                secretRoot = vault.toString(), secretFile = secret.toString())
            val bootstrap = PostgresNewGameHost.approve(settings)
            val receipt = bootstrap.prepare()
            sql(database, "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO $login")
            sql(database, "GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO $login")
            sql(database, "REVOKE $owner FROM $login")
            val initial = bootstrap.initialize(receipt)
            val shop = Files.createDirectory(run.resolve("shop_data")).toRealPath()
            val shopIdentity = PostgresNewGameHost.approve(settings.copy(worldRoot = shop.toString())).binding.worldIdentity
            val hostSettings = PostgresOfflineLookupSettings(binding = bootstrap.binding,
                shop = PostgresRunShopBinding(shop.toString(), shopIdentity),
                allocationOperationId = settings.operationId, allocationRequestSha256 = settings.requestSha256,
                capacityConfirmationSha256 = settings.capacityConfirmationSha256, connection = settings.connection,
                secretRoot = settings.secretRoot, secretFile = settings.secretFile)
            val config = run.resolve("host-settings.json")
            Files.write(config, PostgresOfflineLookupCodec.encodeSettings(hostSettings))
            val current = world.resolve("sushieric/run-state-v2/current.json")
            fun reference() = PostgresHostReference(runState = Files.readString(current), shop = hostSettings.shop,
                javaExecutable = Path.of(System.getProperty("postgresPortHostJavaHome"), "bin/java.exe").toString(),
                runtimeDirectory = work.resolve("host-runtime").toString(), settingsFile = config.toString())
            // NEW_GAMEは正常なゲーム保存ではなく、Hで空履歴へ読み替えません。
            assertFails { LocalHistorySource(reference()).open() }
            assertEquals(initial.state, PostgresRunCodec.decode(Files.readAllBytes(current)))
            val writer = PostgresWriterHost.approve(hostSettings)
            writer.beginGame(initial, UUID.randomUUID()).use { game ->
                val saved = game.prepareSave()
                game.completeSave(PostgresGameSaveEvidence(saved, PostgresSaveCompletion.COMPLETE,
                    PostgresSaveCompletion.COMPLETE, PostgresSaveCompletion.COMPLETE, "c".repeat(64), "d".repeat(64)))
            }
            val healthy = Files.readAllBytes(current)
            for (connection in listOf(
                hostSettings.connection + ("url" to "jdbc:postgresql://other/db"),
                hostSettings.connection + ("passfile" to "other"),
                hostSettings.connection + ("database" to "dbname=other user=other"),
                hostSettings.connection + ("role" to "other_role"))) {
                Files.write(config, PostgresOfflineLookupCodec.encodeSettings(hostSettings.copy(connection = connection)))
                assertFails { LocalHistorySource(reference()).open() }
                assertContentEquals(healthy, Files.readAllBytes(current))
            }
            Files.write(config, PostgresOfflineLookupCodec.encodeSettings(hostSettings.copy(
                binding = hostSettings.binding.copy(databaseUuid = UUID.randomUUID().toString()))))
            assertFails { LocalHistorySource(reference()).open() }
            assertContentEquals(healthy, Files.readAllBytes(current))
            Files.write(config, PostgresOfflineLookupCodec.encodeSettings(hostSettings))
            sql(database, "REVOKE SELECT ON database_identity FROM $login")
            assertFails { LocalHistorySource(reference()).open() }
            assertContentEquals(healthy, Files.readAllBytes(current))
            sql(database, "GRANT SELECT ON database_identity TO $login")
            val unadopted = Files.createDirectory(run.resolve("restore-target-before-adopt")).toRealPath()
            val targetBinding = PostgresNewGameHost.approve(settings.copy(worldRoot = unadopted.toString())).binding
            Files.write(config, PostgresOfflineLookupCodec.encodeSettings(hostSettings.copy(binding = targetBinding)))
            LocalShopTransport(reference()).use { rejected ->
                val pendingTarget = run.resolve("before-adopt/pending.json")
                val guarded = ShopSaveSession(rejected, pendingTarget)
                val request = HostAdapterAction.Edit(listOf(HostProductChange.Save("tab.not_adopted", null,
                    HostDefinition("missing-item", 10, 5), HostStockEdit.Unchanged)))
                assertEquals(HostAdapterStatus.UNKNOWN, guarded.execute(request).status)
                assertNotNull(guarded.pending())
                assertFalse(Files.exists(unadopted.resolve("sushieric/run-state-v2/current.json")))
                assertContentEquals(healthy, Files.readAllBytes(current))
            }
            Files.write(config, PostgresOfflineLookupCodec.encodeSettings(hostSettings))
            val transport = LocalShopTransport(reference()).also { transports += it }
            val pending = run.resolve("pending/request.json")
            var lost = false
            val wrapped = object : ShopTransport by transport {
                override fun execute(request: HostAdapterRequest): HostAdapterResponse =
                    transport.execute(request).also {
                        if (lost && request.action is HostAdapterAction.Edit) { lost = false; throw java.io.IOException("応答喪失") }
                    }
            }
            val session = ShopSaveSession(wrapped, pending)
            val register = HostAdapterAction.Edit(listOf(HostProductChange.Save("tab.apple", null,
                HostDefinition("missing-item", 100, 50), HostStockEdit.Register(12, null))))
            assertEquals(HostAdapterStatus.SUCCESS, session.execute(register).status)
            val product = assertIs<HostAdapterResult.Products>(session.read(listOf("tab.apple")).result).products.single()
            assertEquals(12, product.stock?.quantity)
            val priceOnly = HostAdapterAction.Edit(listOf(HostProductChange.Save(product.id, product.definitionSha256,
                product.definition.copy(purchasePrice = 200), HostStockEdit.Unchanged)))
            lost = true
            assertEquals(HostAdapterStatus.UNKNOWN, session.execute(priceOnly).status)
            val original = assertNotNull(session.pending())
            val before = Files.readAllBytes(current)
            assertEquals(HostAdapterStatus.SUCCESS, session.reconcile().status)
            assertNull(session.pending())
            assertContentEquals(before, Files.readAllBytes(current))
            assertEquals(PostgresOfflineLookupCodec.requestHash(original), PostgresOfflineLookupHost.approve(hostSettings).observe(original).requestSha256)
            val local = LocalEditorDataStore(world.toFile(), hostReference = reference())
            try {
                Files.createDirectories(world.resolve("shop_data"))
                Files.writeString(world.resolve("shop_data/decoy.yml"), "別のshop")
                val listed = assertIs<StoreResult.Success<List<StoreResource>>>(local.list(EditorDataDescriptors.shop)).value
                assertEquals(listOf("tab.apple"), listed.map { it.id })
                assertEquals(shop.resolve("tab/apple.yml").toFile().absolutePath, listed.single().location)
                assertEquals(listOf("tab"), assertIs<StoreResult.Success<List<String>>>(
                    local.listDirectories(EditorDataDescriptors.shop)).value)
                val loaded = assertIs<StoreResult.Success<*>>(local.load(EditorDataDescriptors.shop, "tab.apple")).value
                val read = assertIs<MutableShopProductData>(loaded)
                assertEquals(200, read.purchasePrice)
                assertEquals(ShopProductStock.Current(requireNotNull(product.stock).quantity,
                    requireNotNull(product.stock).revision), read.stockBaseline)
                requireNotNull(local.historySource).open().use { search ->
                    assertIs<StoreResult.Success<*>>(local.load(EditorDataDescriptors.shop, "tab.apple"))
                    read.purchasePrice = 201
                    assertIs<StoreResult.Success<*>>(local.save(EditorDataDescriptors.shop, read.id, read))
                    assertEquals(1, search.next().size)
                    assertTrue(search.next().isEmpty())
                }
                assertIs<StoreResult.Success<*>>(local.load(EditorDataDescriptors.shop, "tab.apple"))
                assertEquals(201, requireNotNull(local.shopProducts?.snapshot("tab.apple")).definition.purchasePrice)
                assertFails { reference().copy(shop = hostSettings.shop.copy(root = world.toString())).validate() }
            } finally { local.closeDatabase() }
            LocalHistorySource(reference()).open().use { search ->
                assertEquals(1, search.next().size)
                assertTrue(search.next().isEmpty())
            }
            // 試験専用DBの作用点でdisk_fullを返し、file/DB部分成功を成功にしません。
            sql(database, "CREATE FUNCTION fixture_disk_full() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION USING ERRCODE=''53100'', MESSAGE=''fixture disk full''; END'")
            sql(database, "CREATE TRIGGER fixture_disk_full BEFORE INSERT ON shop_stocks FOR EACH ROW EXECUTE FUNCTION fixture_disk_full()")
            val failing = LocalShopTransport(reference()).also { transports += it }
            val failedSession = ShopSaveSession(failing, run.resolve("capacity/pending.json"))
            val edit = register.copy(products = listOf(HostProductChange.Save("tab.fail", null,
                HostDefinition("missing-item", 10, 5), HostStockEdit.Register(3, null))))
            assertEquals(HostAdapterStatus.UNKNOWN, failedSession.execute(edit).status)
            val preserved = assertNotNull(failedSession.pending())
            assertEquals(preserved, failedSession.pending())
            assertFails { failedSession.execute(edit) }
            assertFails { LocalHistorySource(reference()).open() }
            failing.close()
        } finally {
            Files.deleteIfExists(secret)
        }
    }
}
