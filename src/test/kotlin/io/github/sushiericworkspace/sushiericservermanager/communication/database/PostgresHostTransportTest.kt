package io.github.sushiericworkspace.sushiericservermanager.communication.database

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericdatamodel.database.run.PostgresRunCodec
import io.github.sushiericworkspace.sushiericservermanager.config.DatabaseHistoryHost
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.ShopSaveSession
import io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory.transportHistory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class PostgresHostTransportTest {
    @TempDir lateinit var root: Path
    private fun edit(reference: PostgresHostReference) = HostAdapterRequest(requestId = UUID.randomUUID().toString(),
        worldUuid = reference.status.state.binding.worldUuid, action = HostAdapterAction.Edit(emptyList()))

    @Test fun `同じHで複数編集しSAVEDのrunとrevisionを次の読取へ引き継ぐ`() {
        val reference = postgresReference()
        val channel = HostChannelFixture(reference)
        var starts = 0
        val transport = PostgresHostTransport(reference, { starts++; channel })
        repeat(2) { assertEquals(HostAdapterStatus.SUCCESS, transport.execute(edit(reference)).status) }
        val read = edit(reference).copy(action = HostAdapterAction.Products(emptyList()))
        assertIs<HostAdapterResult.Products>(transport.execute(read).result)
        transport.close()
        assertEquals(1, starts)
        assertEquals(1, channel.commands.count { it is PostgresWriterCommand.Open })
        assertEquals(2, channel.commands.count { it is PostgresWriterCommand.Edit })
        assertTrue(channel.eof && channel.closed)
        assertIs<PostgresWriterCommand.Close>(channel.commands.last())
    }

    @Test fun `検索の全ページは同じ初回高水位と未確定集合を使う`() {
        val reference = postgresReference()
        val channel = HostChannelFixture(reference)
        transportHistory(PostgresHostTransport(reference, { channel }), HostHistoryFilter(fromNano = 123))
            .use { assertTrue(it.next().isEmpty()) }
        assertEquals(1, channel.commands.count { it is PostgresWriterCommand.Open })
        val page = channel.commands.filterIsInstance<PostgresWriterCommand.Read>().last().request.action
        assertEquals(123, assertIs<HostAdapterAction.HistoryPage>(page).filter.fromNano)
        assertTrue(channel.closed)
    }

    @Test fun `旧contractと未知fieldおよびURI conninfoを接続参照へ注入できない`() {
        val reference = postgresReference()
        val state = reference.runState
        for (text in listOf(
            state.replace("\"storageContractVersion\":2", "\"storageContractVersion\":1"),
            state.replace("\"connectionReference\":\"fixture_pg\"", "\"connectionReference\":\"jdbc:postgresql://host/db\""),
            state.replace("\"connectionReference\":\"fixture_pg\"", "\"connectionReference\":\"dbname=other user=other\""),
            state.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"properties\":{\"passfile\":\"other\"}"),
            state.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"database\":\"other\""))) {
            assertFails { reference.copy(runState = text).validate() }
        }
        assertFails { reference.copy(storageContractVersion = 1).validate() }
        assertFails { DatabaseHistoryHost(reference.status.state.binding.worldUuid, "legacy-command").reference() }
        assertFails { DatabaseHistoryHost(UUID.randomUUID().toString(), postgres = reference).reference() }
    }

    @Test fun `別worldとOpen旧応答を要求送信前に拒否しYAMLへ切り替えない`() {
        val reference = postgresReference()
        var started = false
        val transport = PostgresHostTransport(reference, { started = true; error("起動しません") })
        assertFails { transport.execute(edit(reference).copy(worldUuid = UUID.randomUUID().toString())) }
        assertFalse(started)
        transport.close()
        val bad = HostChannelFixture(reference) { command ->
            if (command is PostgresWriterCommand.Open) PostgresWriterResponse(status = PostgresWriterStatus.OPENED,
                sessionUuid = command.sessionUuid, runSha256 = "f".repeat(64), stateRevision = 2) else null
        }
        val stale = PostgresHostTransport(reference, { bad })
        assertFails { stale.execute(edit(reference)) }
        assertEquals(1, bad.commands.size)
        assertTrue(bad.eof && bad.closed)
    }

    @Test fun `adopt前ACL未確定容量不足や不明応答でも保存待ちの元要求を保持する`() {
        val reference = postgresReference()
        for (status in listOf(PostgresWriterStatus.REJECTED, PostgresWriterStatus.UNKNOWN)) {
            var sessionUuid: String? = null
            val channel = HostChannelFixture(reference) { command ->
                if (command is PostgresWriterCommand.Open) sessionUuid = command.sessionUuid
                if (command is PostgresWriterCommand.Edit) PostgresWriterResponse(status = status,
                    sessionUuid = sessionUuid, requestId = command.request.requestId) else null
            }
            val transport = PostgresHostTransport(reference, { channel })
            val pending = root.resolve(status.name).resolve("pending.json")
            val saves = ShopSaveSession(transport, pending)
            val request = edit(reference)
            assertEquals(HostAdapterStatus.UNKNOWN, saves.execute(request).status)
            assertEquals(request, saves.pending())
            assertTrue(Files.exists(pending))
            assertFails { saves.execute(edit(reference)) }
            assertEquals(1, channel.commands.count { it is PostgresWriterCommand.Edit })
            assertTrue(channel.closed)
        }
    }

    @Test fun `参照のDBとgenerationの上書きは同じhashにならない`() {
        val original = postgresReference()
        val binding = original.status.state.binding
        for (state in listOf(
            original.status.state.copy(binding = binding.copy(databaseUuid = UUID.randomUUID().toString())),
            original.status.state.copy(binding = binding.copy(generation = UUID.randomUUID().toString())))) {
            assertFails { PostgresRunCodec.encode(state) }
        }
    }

    @Test fun `同じworldでも別DB世代への保存待ち要求の持出しを拒否し元bytesを保持する`() {
        val reference = postgresReference()
        val channel = HostChannelFixture(reference) { command ->
            if (command is PostgresWriterCommand.Edit) PostgresWriterResponse(status = PostgresWriterStatus.UNKNOWN,
                requestId = command.request.requestId) else null
        }
        val file = root.resolve("pending/request.json")
        val session = ShopSaveSession(PostgresHostTransport(reference, { channel }), file)
        session.execute(edit(reference))
        val bytes = Files.readAllBytes(file)
        for (value in listOf(reference.status.state.binding.databaseUuid, reference.status.state.binding.generation)) {
            val replacement = reference.copy(runState = reference.runState.replace(value, UUID.randomUUID().toString()))
            replacement.validate()
            val other = PostgresHostTransport(replacement, { error("別DBへ送信しません") })
            assertFails { ShopSaveSession(other, file).reconcile() }
            assertContentEquals(bytes, Files.readAllBytes(file))
            other.close()
        }
    }

    @Test fun `結果照合はEditを再送せずCOMPLETE以外では元要求を残す`() {
        val reference = postgresReference()
        for (outcome in listOf(PostgresOfflineLookupOutcome.COMPLETE, PostgresOfflineLookupOutcome.NOT_FOUND,
            PostgresOfflineLookupOutcome.INCOMPLETE, PostgresOfflineLookupOutcome.UNAVAILABLE)) {
            val request = edit(reference)
            var writerStarts = 0
            var lookupStarts = 0
            val sent = java.io.ByteArrayOutputStream()
            val lookup = object : HostChannel {
                override val input = java.io.ByteArrayInputStream(PostgresOfflineLookupCodec.encodeResponse(
                    PostgresOfflineLookupResponse(outcome, request.requestId,
                        PostgresOfflineLookupCodec.requestHash(request), reference.status.sha256)))
                override val output = sent
                override val error = java.io.ByteArrayInputStream(byteArrayOf())
                override fun awaitExit(timeoutMillis: Long) = when (outcome) {
                    PostgresOfflineLookupOutcome.COMPLETE -> 0
                    PostgresOfflineLookupOutcome.NOT_FOUND -> 2
                    else -> 3
                }
                override fun close() = Unit
            }
            val writer = HostChannelFixture(reference) { command ->
                if (command is PostgresWriterCommand.Edit) PostgresWriterResponse(status = PostgresWriterStatus.UNKNOWN,
                    requestId = command.request.requestId) else null
            }
            val transport = PostgresHostTransport(reference, { observe ->
                if (observe) { lookupStarts++; lookup } else { writerStarts++; writer }
            })
            val session = ShopSaveSession(transport, root.resolve(outcome.name).resolve("request.json"))
            assertEquals(HostAdapterStatus.UNKNOWN, session.execute(request).status)
            val response = session.reconcile()
            assertEquals(if (outcome == PostgresOfflineLookupOutcome.COMPLETE) HostAdapterStatus.SUCCESS
                else HostAdapterStatus.UNKNOWN, response.status)
            assertEquals(if (outcome == PostgresOfflineLookupOutcome.COMPLETE) null else request, session.pending())
            assertEquals(request, HostAdapterCodec.decodeRequest(sent.toByteArray()))
            assertEquals(1, writerStarts)
            assertEquals(1, lookupStarts)
            assertEquals(1, writer.commands.count { it is PostgresWriterCommand.Edit })
        }
    }
}
