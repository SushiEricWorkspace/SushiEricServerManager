package io.github.sushiericworkspace.sushiericservermanager.communication.database

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue

internal fun postgresReference() = PostgresHostReference(
    runState = requireNotNull(PostgresHostTransportTest::class.java.getResource("/postgres-port-run-saved.json")).readText(),
    javaExecutable = "/fixed/java", runtimeDirectory = "/fixed/host", settingsFile = "/protected/host.json")

/** stream上の実frameを復号し、テスト側が指定したHost応答だけを返します。 */
internal class HostChannelFixture(
    val reference: PostgresHostReference = postgresReference(),
    private val overrideResponse: (PostgresWriterCommand) -> PostgresWriterResponse? = { null }
) : HostChannel {
    val commands = mutableListOf<PostgresWriterCommand>()
    private val incoming = LinkedBlockingQueue<Int>()
    private var session: String? = null
    private var hash = reference.status.sha256
    private var revision = reference.status.state.stateRevision
    var exit = 0
    var eof = false
    var closed = false
    override val error = ByteArrayInputStream(byteArrayOf())
    override val input = object : InputStream() {
        override fun read(): Int = incoming.take()
    }
    override val output = object : ByteArrayOutputStream() {
        override fun flush() {
            val bytes = toByteArray()
            if (bytes.isEmpty()) return
            reset()
            val command = PostgresWriterCodec.decodeRequest(requireNotNull(
                PostgresWriterCodec.readFrame(ByteArrayInputStream(bytes)))).command
            commands += command
            val response = overrideResponse(command) ?: when (command) {
                is PostgresWriterCommand.Open -> {
                    session = command.sessionUuid
                    PostgresWriterResponse(status = PostgresWriterStatus.OPENED, sessionUuid = session,
                        runSha256 = hash, stateRevision = revision)
                }
                is PostgresWriterCommand.Read -> PostgresWriterResponse(status = PostgresWriterStatus.READ,
                    sessionUuid = session, requestId = command.request.requestId, runSha256 = hash,
                    stateRevision = revision, result = when (val action = command.request.action) {
                        HostAdapterAction.HistoryStart -> HostAdapterResult.Snapshot(HostHistorySnapshot(7, listOf(3)))
                        is HostAdapterAction.HistoryPage -> {
                            require(action.snapshot == HostHistorySnapshot(7, listOf(3)))
                            HostAdapterResult.History(emptyList())
                        }
                        is HostAdapterAction.Products -> HostAdapterResult.Products(emptyList())
                        else -> error("未指定の読取です。")
                    })
                is PostgresWriterCommand.Edit -> {
                    revision += 2
                    hash = "d".repeat(64)
                    PostgresWriterResponse(status = PostgresWriterStatus.SAVED, sessionUuid = session,
                        requestId = command.request.requestId, runSha256 = hash, stateRevision = revision)
                }
                PostgresWriterCommand.Close -> PostgresWriterResponse(status = PostgresWriterStatus.CLOSED,
                    sessionUuid = session)
            }
            val frame = ByteArrayOutputStream()
            PostgresWriterCodec.writeFrame(frame, PostgresWriterCodec.encodeResponse(response))
            frame.toByteArray().forEach { incoming.put(it.toInt() and 255) }
        }
        override fun close() { eof = true; incoming.offer(-1) }
    }
    override fun awaitExit(timeoutMillis: Long) = exit
    override fun close() { output.close(); closed = true }
}

internal fun stubSshRunState(client: net.schmizz.sshj.SSHClient, reference: PostgresHostReference) {
    val sftp = org.mockito.Mockito.mock(net.schmizz.sshj.sftp.SFTPClient::class.java)
    val file = org.mockito.Mockito.mock(net.schmizz.sshj.sftp.RemoteFile::class.java)
    val bytes = reference.runState.encodeToByteArray()
    org.mockito.Mockito.`when`(client.newSFTPClient()).thenReturn(sftp)
    org.mockito.Mockito.`when`(sftp.open(reference.runStatePath().replace('\\', '/'))).thenReturn(file)
    org.mockito.Mockito.`when`(file.read(org.mockito.Mockito.anyLong(), org.mockito.Mockito.any(ByteArray::class.java),
        org.mockito.Mockito.anyInt(), org.mockito.Mockito.anyInt())).thenAnswer { call ->
        val offset = call.getArgument<Long>(0).toInt()
        if (offset >= bytes.size) -1 else {
            val target = call.getArgument<ByteArray>(1)
            val start = call.getArgument<Int>(2)
            val length = minOf(call.getArgument<Int>(3), bytes.size - offset)
            bytes.copyInto(target, start, offset, offset + length)
            length
        }
    }
}
