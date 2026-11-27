package io.github.sushiericworkspace.sushiericservermanager.communication.database

import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import net.schmizz.sshj.SSHClient
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 一つの固定helperのstdioと終了観測。closeはstdinをEOFにしてから所有するchannelだけを解放します。 */
internal interface HostChannel : AutoCloseable {
    val input: InputStream
    val output: OutputStream
    val error: InputStream
    fun awaitExit(timeoutMillis: Long): Int
}

internal fun localRunState(reference: PostgresHostReference) =
    Files.newInputStream(Path.of(reference.runStatePath())).use { it.readNBytes(16 * 1024 * 1024 + 1) }

/** SFTPは非秘密run記録一件だけに使用し、DBや保護設定は取得しません。 */
internal fun sshRunState(client: SSHClient, reference: PostgresHostReference): ByteArray =
    client.newSFTPClient().use { sftp ->
        sftp.open(reference.runStatePath().replace('\\', '/')).use { file ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (bytes.size() <= 16 * 1024 * 1024) {
                val count = file.read(bytes.size().toLong(), buffer, 0, buffer.size)
                if (count == -1) break
                check(count > 0)
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    }

internal fun localHostChannel(reference: PostgresHostReference, lookup: Boolean): HostChannel {
    val args = reference.arguments(lookup)
    require(listOf(reference.javaExecutable, reference.runtimeDirectory, reference.settingsFile).all { Path.of(it).isAbsolute })
    val process = ProcessBuilder(args).start()
    return object : HostChannel {
        override val input = process.inputStream
        override val output = process.outputStream
        override val error = process.errorStream
        override fun awaitExit(timeoutMillis: Long): Int {
            check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "Host helperの終了を確認できません。" }
            return process.exitValue()
        }
        override fun close() {
            output.close()
            awaitExit(30_000)
            input.close()
            error.close()
        }
    }
}

internal fun sshHostChannel(client: SSHClient, reference: PostgresHostReference,
    os: RemoteOperatingSystem, lookup: Boolean): HostChannel {
    val args = reference.arguments(lookup)
    val commandLine = if (os.family == RemoteOperatingSystem.Family.WINDOWS) {
        // Windows OpenSSHのcmdで展開される文字を許可しません。
        require(args.all { arg -> arg.none { it in "\"%!`&|<>^" } })
        require(listOf(reference.javaExecutable, reference.runtimeDirectory, reference.settingsFile).all {
            Regex("[A-Za-z]:[/\\\\].+").matches(it)
        })
        args.joinToString(" ") { "\"" + it + "\"" }
    } else {
        require(listOf(reference.javaExecutable, reference.runtimeDirectory, reference.settingsFile).all { it.startsWith("/") })
        args.joinToString(" ") { "'" + it.replace("'", "'\"'\"'") + "'" }
    }
    val session = client.startSession()
    val command = try { session.exec(commandLine) } catch (failure: Exception) { session.close(); throw failure }
    return object : HostChannel {
        override val input = command.inputStream
        override val output = command.outputStream
        override val error = command.errorStream
        override fun awaitExit(timeoutMillis: Long): Int {
            command.join(timeoutMillis, TimeUnit.MILLISECONDS)
            return requireNotNull(command.exitStatus) { "SSH Host helperの終了を確認できません。" }
        }
        override fun close() {
            try { output.close(); awaitExit(30_000) }
            finally { try { command.close() } finally { session.close() } }
        }
    }
}
