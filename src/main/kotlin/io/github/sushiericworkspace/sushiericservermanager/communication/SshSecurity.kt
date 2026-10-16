package io.github.sushiericworkspace.sushiericservermanager.communication

import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.config.OS
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

fun interface HostKeyApprovalHandler {
    fun approve(prompt: HostKeyPrompt): Boolean

    companion object {
        val REJECT_UNKNOWN: HostKeyApprovalHandler = HostKeyApprovalHandler { false }
    }
}

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String
)

enum class HostKeyRejectionReason {
    NOT_APPROVED,
    CHANGED,
    STORE_FAILED
}

object HostKeyFingerprint {
    fun format(key: PublicKey): String = SecurityUtils.getFingerprint(key)
}

/**
 * アプリ管理のOpenSSH known_hostsを使用するホスト鍵検証器。
 * 初回だけGUI承認を要求し、変更済みホスト鍵と破損ファイルはフェイルクローズします。
 */
class ManagedKnownHostsVerifier(
    private val approvalHandler: HostKeyApprovalHandler,
    knownHostsFile: File = FilePath.KNOWN_HOSTS.toFile()
) : OpenSSHKnownHosts(prepareKnownHostsFile(knownHostsFile)) {

    @Volatile
    var rejectionReason: HostKeyRejectionReason? = null
        private set

    @Volatile
    var lastPrompt: HostKeyPrompt? = null
        private set

    @Volatile
    var previousFingerprint: String? = null
        private set

    private var rawHost: String = ""
    private var rawPort: Int = 22

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        rawHost = hostname
        rawPort = port
        lastPrompt = HostKeyPrompt(
            host = hostname,
            port = port,
            algorithm = KeyType.fromKey(key).toString(),
            fingerprint = HostKeyFingerprint.format(key)
        )

        if (entries().any { it is BadHostEntry }) {
            rejectionReason = HostKeyRejectionReason.STORE_FAILED
            return false
        }

        return super.verify(hostname, port, key)
    }

    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
        val prompt = lastPrompt ?: HostKeyPrompt(
            host = rawHost,
            port = rawPort,
            algorithm = KeyType.fromKey(key).toString(),
            fingerprint = HostKeyFingerprint.format(key)
        )

        if (!approvalHandler.approve(prompt)) {
            rejectionReason = HostKeyRejectionReason.NOT_APPROVED
            return false
        }

        return try {
            val entry = HostEntry(
                null,
                hostname,
                KeyType.fromKey(key),
                key,
                "SushiEricServerManager"
            )
            write(entry)
            entries().add(entry)
            SecureFilePermissions.restrictFile(file.toPath())
            true
        } catch (_: Exception) {
            rejectionReason = HostKeyRejectionReason.STORE_FAILED
            false
        }
    }

    override fun hostKeyChangedAction(hostname: String, key: PublicKey): Boolean {
        val keyType = KeyType.fromKey(key)
        previousFingerprint = entries()
            .asSequence()
            .filterNot { it is BadHostEntry }
            .firstOrNull { entry ->
                runCatching { entry.appliesTo(keyType, hostname) }.getOrDefault(false)
            }
            ?.fingerprint
        rejectionReason = HostKeyRejectionReason.CHANGED
        return false
    }

    companion object {
        private fun prepareKnownHostsFile(file: File): File {
            val parent = file.parentFile
                ?: throw IOException("known_hosts parent directory is unavailable")
            Files.createDirectories(parent.toPath())
            SecureFilePermissions.restrictDirectory(parent.toPath())
            if (file.exists()) {
                if (!file.isFile) throw IOException("known_hosts path is not a regular file")
                SecureFilePermissions.restrictFile(file.toPath())
            }
            return file
        }
    }
}

data class GeneratedSshKey(
    val privateKeyPath: Path,
    val publicKeyPath: Path,
    val publicKeyLine: String,
    val keyFormat: String
)

object SafeKeyFileName {
    private val pattern = Regex(
        "^profile-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.key$"
    )

    fun generate(id: UUID = UUID.randomUUID()): String = "profile-$id.key"

    fun isSafe(fileName: String): Boolean = pattern.matches(fileName)
}

/**
 * 外部ssh-keygenを使わずJDKで鍵ペアを生成します。
 * Ed25519を優先し、SSHJが保存形式を読めない場合はRSA 3072bitへフォールバックします。
 */
class SshKeyGenerator(
    private val keyDirectory: Path = FilePath.SSH_DIR.toFile().toPath()
) {
    fun generate(): GeneratedSshKey {
        ensureBouncyCastle()
        Files.createDirectories(keyDirectory)
        SecureFilePermissions.restrictDirectory(keyDirectory)

        val ed25519 = runCatching {
            generateAndPersist(
                keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
                keyFormat = "PKCS8_ED25519"
            )
        }
        if (ed25519.isSuccess) return ed25519.getOrThrow()

        return generateAndPersist(
            keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair(),
            keyFormat = "PKCS8_RSA_3072"
        )
    }

    fun deleteGeneratedKey(key: GeneratedSshKey) {
        Files.deleteIfExists(key.privateKeyPath)
        Files.deleteIfExists(key.publicKeyPath)
    }

    private fun generateAndPersist(keyPair: KeyPair, keyFormat: String): GeneratedSshKey {
        val fileName = SafeKeyFileName.generate()
        check(SafeKeyFileName.isSafe(fileName))

        val privateKeyPath = keyDirectory.resolve(fileName)
        val publicKeyPath = keyDirectory.resolve("$fileName.pub")
        val comment = "SushiEricServerManager-${fileName.removeSuffix(".key")}" 
        val publicKeyLine = OpenSshPublicKeyEncoder.encode(keyPair.public, comment)
        val privatePem = PemEncoder.encodePrivateKey(keyPair)

        try {
            Files.writeString(
                privateKeyPath,
                privatePem,
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            SecureFilePermissions.restrictFile(privateKeyPath)

            Files.writeString(
                publicKeyPath,
                "$publicKeyLine\n",
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            SecureFilePermissions.restrictFile(publicKeyPath)

            SSHClient().use { client ->
                val provider = client.loadKeys(privateKeyPath.toString())
                provider.public
                provider.private
            }

            return GeneratedSshKey(
                privateKeyPath = privateKeyPath,
                publicKeyPath = publicKeyPath,
                publicKeyLine = publicKeyLine,
                keyFormat = keyFormat
            )
        } catch (e: Exception) {
            Files.deleteIfExists(privateKeyPath)
            Files.deleteIfExists(publicKeyPath)
            throw e
        }
    }

    private fun ensureBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}

private object PemEncoder {
    fun encodePrivateKey(keyPair: KeyPair): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII))
            .encodeToString(keyPair.private.encoded)

        return buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            appendLine(body)
            appendLine("-----END PRIVATE KEY-----")
        }
    }
}

object OpenSshPublicKeyEncoder {
    fun encode(key: PublicKey, comment: String): String {
        val (type, blob) = when (key) {
            is EdECPublicKey -> "ssh-ed25519" to encodeEd25519(key)
            is RSAPublicKey -> "ssh-rsa" to encodeRsa(key)
            else -> throw IllegalArgumentException("Unsupported public key algorithm: ${key.algorithm}")
        }

        return "$type ${Base64.getEncoder().encodeToString(blob)} $comment"
    }

    private fun encodeEd25519(key: EdECPublicKey): ByteArray {
        val raw = toLittleEndianUnsigned(key.point.y, 32)
        if (key.point.isXOdd) raw[31] = (raw[31].toInt() or 0x80).toByte()

        return sshBlob {
            writeSshString("ssh-ed25519".toByteArray(StandardCharsets.US_ASCII))
            writeSshString(raw)
        }
    }

    private fun encodeRsa(key: RSAPublicKey): ByteArray = sshBlob {
        writeSshString("ssh-rsa".toByteArray(StandardCharsets.US_ASCII))
        writeSshString(key.publicExponent.toByteArray())
        writeSshString(key.modulus.toByteArray())
    }

    private fun toLittleEndianUnsigned(value: BigInteger, size: Int): ByteArray {
        val encoded = value.toByteArray()
        val unsigned = if (encoded.size > 1 && encoded[0] == 0.toByte()) {
            encoded.copyOfRange(1, encoded.size)
        } else {
            encoded
        }
        require(unsigned.size <= size) { "Ed25519 coordinate is too large" }

        return ByteArray(size).also { output ->
            unsigned.indices.forEach { index ->
                output[index] = unsigned[unsigned.lastIndex - index]
            }
        }
    }

    private fun sshBlob(block: SshBlobWriter.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { dataOutput -> SshBlobWriter(dataOutput).block() }
        return output.toByteArray()
    }

    private class SshBlobWriter(private val output: DataOutputStream) {
        fun writeSshString(value: ByteArray) {
            output.writeInt(value.size)
            output.write(value)
        }
    }
}

object SecureFilePermissions {
    private val ownerReadWrite = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    )
    private val ownerDirectory = ownerReadWrite + PosixFilePermission.OWNER_EXECUTE

    fun restrictFile(path: Path) = restrict(path, directory = false)

    fun restrictDirectory(path: Path) = restrict(path, directory = true)

    private fun restrict(path: Path, directory: Boolean) {
        when {
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix") -> {
                Files.setPosixFilePermissions(path, if (directory) ownerDirectory else ownerReadWrite)
            }

            OS.isWindows -> restrictWindowsAcl(path, directory)
            else -> throw IOException("POSIX permissions and Windows ACLs are unavailable")
        }
    }

    private fun restrictWindowsAcl(path: Path, directory: Boolean) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: throw IOException("Windows ACL view is not available")
        val owner = Files.getOwner(path)

        val permissions = EnumSet.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.DELETE,
            AclEntryPermission.SYNCHRONIZE
        )
        if (directory) {
            permissions.add(AclEntryPermission.EXECUTE)
            permissions.add(AclEntryPermission.DELETE_CHILD)
        }

        val ownerEntry = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(permissions)
            .build()

        view.acl = listOf(ownerEntry)
        if (view.acl.any { it.type() == AclEntryType.ALLOW && it.principal() != owner }) {
            throw IOException("Windows ACL still grants another principal access")
        }
    }
}

data class AuthorizedKeysMergeResult(
    val content: String,
    val appendText: String,
    val added: Boolean
)

object AuthorizedKeysEditor {
    private data class Identity(val type: String, val body: String)

    fun merge(existingContent: String, newPublicKey: String): AuthorizedKeysMergeResult {
        val normalizedNewKey = newPublicKey.trim()
        val newIdentity = parseIdentity(normalizedNewKey)
            ?: throw IllegalArgumentException("Invalid OpenSSH public key")

        val duplicate = existingContent.lineSequence().mapNotNull(::parseIdentity).any { it == newIdentity }
        if (duplicate) return AuthorizedKeysMergeResult(existingContent, "", added = false)

        val appendText = when {
            existingContent.isEmpty() -> "$normalizedNewKey\n"
            existingContent.endsWith("\n") || existingContent.endsWith("\r") -> "$normalizedNewKey\n"
            else -> "\n$normalizedNewKey\n"
        }
        return AuthorizedKeysMergeResult(existingContent + appendText, appendText, added = true)
    }

    private fun parseIdentity(line: String): Identity? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

        val parts = trimmed.split(Regex("\\s+"))
        val typeIndex = parts.indexOfFirst { token ->
            token.startsWith("ssh-") || token.startsWith("ecdsa-") || token.startsWith("sk-")
        }
        if (typeIndex < 0 || typeIndex + 1 >= parts.size) return null
        val type = parts[typeIndex]

        val decoded = runCatching { Base64.getDecoder().decode(parts[typeIndex + 1]) }.getOrNull() ?: return null
        if (!hasMatchingKeyType(decoded, type)) return null
        return Identity(type, Base64.getEncoder().encodeToString(decoded))
    }

    private fun hasMatchingKeyType(decoded: ByteArray, declaredType: String): Boolean {
        return runCatching {
            DataInputStream(ByteArrayInputStream(decoded)).use { input ->
                val length = input.readInt()
                if (length <= 0 || length > decoded.size - Int.SIZE_BYTES) return@use false
                val typeBytes = ByteArray(length)
                input.readFully(typeBytes)
                String(typeBytes, StandardCharsets.US_ASCII) == declaredType
            }
        }.getOrDefault(false)
    }
}

/**
 * 接続先OSに応じて公開鍵登録方法を切り替えます。
 * Ubuntu Desktop/ServerとmacOSはSFTP、Windowsは圧縮したPowerShell EncodedCommandを使用します。
 */
class AuthorizedKeysRegistrar(
    private val unixRegistrar: UnixAuthorizedKeysRegistrar = UnixAuthorizedKeysRegistrar(),
    private val windowsRegistrar: WindowsAuthorizedKeysRegistrar = WindowsAuthorizedKeysRegistrar()
) {
    fun register(
        connected: ConnectedSsh,
        remoteOperatingSystem: RemoteOperatingSystem,
        publicKeyLine: String
    ): Boolean {
        return when (remoteOperatingSystem.family) {
            RemoteOperatingSystem.Family.UNIX_LIKE ->
                unixRegistrar.register(connected.sftpClient, publicKeyLine)

            RemoteOperatingSystem.Family.WINDOWS ->
                windowsRegistrar.register(connected.client, publicKeyLine)
        }
    }

    /** Ubuntu系を前提としていた以前の内部呼び出しとの互換用。 */
    @Deprecated("Use register(connected, remoteOperatingSystem, publicKeyLine)")
    fun register(sftp: SFTPClient, publicKeyLine: String): Boolean =
        unixRegistrar.register(sftp, publicKeyLine)
}

class UnixAuthorizedKeysRegistrar {
    companion object {
        private const val DIRECTORY_MODE = 448 // 0700
        private const val FILE_MODE = 384 // 0600
    }

    fun register(sftp: SFTPClient, publicKeyLine: String): Boolean {
        val home = sftp.canonicalize(".").trimEnd('/').ifEmpty { "/" }
        val sshDirectory = if (home == "/") "/.ssh" else "$home/.ssh"
        val authorizedKeys = "$sshDirectory/authorized_keys"

        ensureDirectory(sftp, sshDirectory)
        sftp.chmod(sshDirectory, DIRECTORY_MODE)

        val existing = readTextOrEmpty(sftp, authorizedKeys)
        val merged = AuthorizedKeysEditor.merge(existing, publicKeyLine)

        if (merged.added) appendText(sftp, authorizedKeys, merged.appendText)
        sftp.chmod(authorizedKeys, FILE_MODE)
        return merged.added
    }

    private fun ensureDirectory(sftp: SFTPClient, path: String) {
        try {
            if (sftp.stat(path).type != FileMode.Type.DIRECTORY) {
                throw IOException("Remote .ssh path is not a directory")
            }
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) sftp.mkdir(path) else throw e
        }
    }

    private fun appendText(sftp: SFTPClient, path: String, text: String) {
        sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.APPEND)).use { remoteFile ->
            remoteFile.RemoteFileOutputStream().use { output ->
                output.write(text.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
        }
    }

    private fun readTextOrEmpty(sftp: SFTPClient, remotePath: String): String {
        val temporaryFile = Files.createTempFile("sushieric-authorized-keys-", ".tmp")
        return try {
            try {
                sftp.get(remotePath, temporaryFile.toString())
                Files.readString(temporaryFile, StandardCharsets.UTF_8)
            } catch (e: SFTPException) {
                if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) "" else throw e
            }
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }
}

/**
 * Windows OpenSSH Server用。
 * 標準ユーザーは%USERPROFILE%\\.ssh\\authorized_keys、管理者は
 * %ProgramData%\\ssh\\administrators_authorized_keysへ登録し、Microsoft推奨ACLを設定します。
 */
class WindowsAuthorizedKeysRegistrar(
    private val commandTimeoutSeconds: Long = 30
) {
    fun register(client: SSHClient, publicKeyLine: String): Boolean {
        val script = WindowsAuthorizedKeysScript.build(publicKeyLine)
        val commandLine = WindowsPowerShellCommand.build(script)
        val keySummary = WindowsPublicKeySummary.from(publicKeyLine)

        client.startSession().use { session ->
            session.exec(commandLine).use { command ->
                command.join(commandTimeoutSeconds, TimeUnit.SECONDS)
                val output = command.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val errorOutput = command.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                return WindowsAuthorizedKeysCommandResultParser.parse(
                    exitStatus = command.exitStatus,
                    output = output,
                    errorOutput = errorOutput,
                    keySummary = keySummary
                ).added
            }
        }
    }
}

internal object WindowsPowerShellCommand {
    private const val PREFIX =
        "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand "

    fun build(script: String): String {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { gzip ->
                gzip.write(script.toByteArray(StandardCharsets.UTF_8))
            }
        }.toByteArray()
        val compressedData = Base64.getEncoder().encodeToString(compressed)
        val launcher = """
            ${'$'}d=[Convert]::FromBase64String('$compressedData')
            ${'$'}m=[IO.MemoryStream]::new(${'$'}d)
            ${'$'}g=[IO.Compression.GzipStream]::new(${'$'}m,[IO.Compression.CompressionMode]::Decompress)
            ${'$'}r=[IO.StreamReader]::new(${'$'}g,[Text.Encoding]::UTF8)
            & ([ScriptBlock]::Create(${'$'}r.ReadToEnd()))
        """.trimIndent()
        val encodedLauncher = Base64.getEncoder()
            .encodeToString(launcher.toByteArray(StandardCharsets.UTF_16LE))
        return PREFIX + encodedLauncher
    }
}

internal enum class WindowsAuthorizedKeysTarget {
    ADMINISTRATORS,
    USER_PROFILE,
    UNKNOWN
}

internal enum class WindowsAuthorizedKeysKeyState {
    NOT_WRITTEN,
    EXISTS,
    APPENDED,
    UNKNOWN
}

internal data class WindowsPublicKeySummary(
    val keyType: String,
    val fingerprint: String
) {
    companion object {
        fun from(publicKeyLine: String): WindowsPublicKeySummary {
            val parts = publicKeyLine.trim().split(Regex("\\s+"))
            require(parts.size >= 2) { "Invalid OpenSSH public key" }
            val blob = Base64.getDecoder().decode(parts[1])
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            val fingerprint = Base64.getEncoder().withoutPadding().encodeToString(digest)
            return WindowsPublicKeySummary(parts[0], "SHA256:$fingerprint")
        }
    }
}

internal data class WindowsAuthorizedKeysCommandResult(
    val added: Boolean,
    val target: WindowsAuthorizedKeysTarget,
    val targetPath: String?,
    val keyState: WindowsAuthorizedKeysKeyState
)

internal class WindowsAuthorizedKeysRegistrationException(
    val exitCode: Int?,
    val errorCode: String,
    val stage: String?,
    val target: WindowsAuthorizedKeysTarget,
    val targetPath: String?,
    val keyState: WindowsAuthorizedKeysKeyState,
    val keySummary: WindowsPublicKeySummary
) : IOException("Windows public-key registration failed: $errorCode") {
    val publicKeyMayBeRegistered: Boolean
        get() = keyState == WindowsAuthorizedKeysKeyState.APPENDED ||
            keyState == WindowsAuthorizedKeysKeyState.EXISTS

    fun safeDiagnostic(): String = buildList {
        add("exitCode=${exitCode ?: "TIMEOUT"}")
        add("code=$errorCode")
        stage?.let { add("stage=$it") }
        add("target=${target.name}")
        targetPath?.let { add("path=$it") }
        add("keyState=${keyState.name}")
        add("keyType=${keySummary.keyType}")
        add("fingerprint=${keySummary.fingerprint}")
    }.joinToString(", ")
}

internal object WindowsAuthorizedKeysCommandResultParser {
    fun parse(
        exitStatus: Int?,
        output: String,
        errorOutput: String,
        keySummary: WindowsPublicKeySummary
    ): WindowsAuthorizedKeysCommandResult {
        val combined = sequenceOf(output, errorOutput).joinToString("\n")
        val target = metadata(combined, "SUSHIERIC_TARGET:")
            ?.let { runCatching { WindowsAuthorizedKeysTarget.valueOf(it) }.getOrNull() }
            ?: WindowsAuthorizedKeysTarget.UNKNOWN
        val targetPath = metadata(combined, "SUSHIERIC_PATH:")
        val keyState = metadata(combined, "SUSHIERIC_KEY_STATE:")
            ?.let { runCatching { WindowsAuthorizedKeysKeyState.valueOf(it) }.getOrNull() }
            ?: WindowsAuthorizedKeysKeyState.UNKNOWN

        if (exitStatus == null || exitStatus != 0) {
            throw WindowsAuthorizedKeysRegistrationException(
                exitCode = exitStatus,
                errorCode = metadata(errorOutput, "SUSHIERIC_ERROR:") ?: if (exitStatus == null) {
                    "TIMEOUT"
                } else {
                    "REMOTE_COMMAND_FAILED"
                },
                stage = metadata(combined, "SUSHIERIC_STAGE:"),
                target = target,
                targetPath = targetPath,
                keyState = keyState,
                keySummary = keySummary
            )
        }

        val result = metadata(output, "SUSHIERIC_RESULT:")
        val added = when (result) {
            "ADDED" -> true
            "EXISTS" -> false
            else -> throw WindowsAuthorizedKeysRegistrationException(
                exitCode = exitStatus,
                errorCode = "INVALID_COMMAND_RESULT",
                stage = metadata(combined, "SUSHIERIC_STAGE:"),
                target = target,
                targetPath = targetPath,
                keyState = keyState,
                keySummary = keySummary
            )
        }
        return WindowsAuthorizedKeysCommandResult(added, target, targetPath, keyState)
    }

    private fun metadata(text: String, prefix: String): String? {
        return text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.take(512)
            ?.takeIf { it.isNotEmpty() }
    }
}

object WindowsAuthorizedKeysScript {
    fun build(publicKeyLine: String): String {
        // ユーザー入力をPowerShell構文へ直接埋め込まず、Base64データとして渡します。
        val keyData = Base64.getEncoder().encodeToString(publicKeyLine.trim().toByteArray(StandardCharsets.UTF_8))
        return """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}stage = 'INITIALIZE'
            ${'$'}target = 'UNKNOWN'
            ${'$'}authorizedKeys = ''
            ${'$'}keyState = 'NOT_WRITTEN'
            try {
                ${'$'}stage = 'VALIDATE_PUBLIC_KEY'
                ${'$'}utf8 = New-Object System.Text.UTF8Encoding(${ '$' }false)
                ${'$'}key = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$keyData')).Trim()
                ${'$'}parts = ${'$'}key -split '\s+'
                if (${ '$' }parts.Count -lt 2) { throw 'INVALID_PUBLIC_KEY' }
                ${'$'}identityValue = "${'$'}(${'$'}parts[0]) ${'$'}(${'$'}parts[1])"

                ${'$'}stage = 'RESOLVE_TARGET'
                ${'$'}identity = [Security.Principal.WindowsIdentity]::GetCurrent()
                ${'$'}administratorsSid = New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')
                ${'$'}isAdministrator = @(${'$'}identity.Groups | Where-Object { ${'$'}_.Value -eq ${'$'}administratorsSid.Value }).Count -gt 0

                if (${ '$' }isAdministrator) {
                    ${'$'}target = 'ADMINISTRATORS'
                    ${'$'}sshDirectory = Join-Path ${'$'}env:ProgramData 'ssh'
                    ${'$'}authorizedKeys = Join-Path ${'$'}sshDirectory 'administrators_authorized_keys'
                } else {
                    ${'$'}target = 'USER_PROFILE'
                    ${'$'}sshDirectory = Join-Path ${'$'}env:USERPROFILE '.ssh'
                    ${'$'}authorizedKeys = Join-Path ${'$'}sshDirectory 'authorized_keys'
                }

                ${'$'}stage = 'PREPARE_DIRECTORY'
                [IO.Directory]::CreateDirectory(${ '$' }sshDirectory) | Out-Null
                ${'$'}exists = ${'$'}false
                ${'$'}stage = 'CHECK_EXISTING_KEY'
                if ([IO.File]::Exists(${ '$' }authorizedKeys)) {
                    foreach (${ '$' }line in [IO.File]::ReadAllLines(${ '$' }authorizedKeys, ${ '$' }utf8)) {
                        ${'$'}lineParts = ${'$'}line.Trim() -split '\s+'
                        if (${ '$' }lineParts.Count -ge 2 -and "${'$'}(${'$'}lineParts[0]) ${'$'}(${'$'}lineParts[1])" -eq ${'$'}identityValue) {
                            ${'$'}exists = ${'$'}true
                            break
                        }
                    }
                }

                if (-not ${ '$' }exists) {
                    ${'$'}stage = 'APPEND_PUBLIC_KEY'
                    [IO.File]::AppendAllText(${ '$' }authorizedKeys, ${'$'}key + [Environment]::NewLine, ${'$'}utf8)
                    ${'$'}keyState = 'APPENDED'
                } else {
                    ${'$'}keyState = 'EXISTS'
                }

                ${'$'}stage = 'SET_FILE_ACL'
                ${'$'}systemSid = New-Object Security.Principal.SecurityIdentifier('S-1-5-18')
                ${'$'}fullControl = [Security.AccessControl.FileSystemRights]::FullControl
                ${'$'}allow = [Security.AccessControl.AccessControlType]::Allow

                ${'$'}fileAcl = [IO.File]::GetAccessControl(${ '$' }authorizedKeys)
                ${'$'}fileAcl.SetAccessRuleProtection(${ '$' }true, ${ '$' }false)
                if (${ '$' }isAdministrator) {
                    ${'$'}fileAcl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(${ '$' }administratorsSid, ${ '$' }fullControl, ${ '$' }allow)))
                } else {
                    ${'$'}fileAcl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(${ '$' }identity.User, ${ '$' }fullControl, ${ '$' }allow)))
                }
                ${'$'}fileAcl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(${ '$' }systemSid, ${ '$' }fullControl, ${ '$' }allow)))
                [IO.File]::SetAccessControl(${ '$' }authorizedKeys, ${ '$' }fileAcl)

                if (-not ${ '$' }isAdministrator) {
                    ${'$'}stage = 'SET_DIRECTORY_ACL'
                    ${'$'}inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
                    ${'$'}propagation = [Security.AccessControl.PropagationFlags]::None
                    ${'$'}directoryAcl = [IO.Directory]::GetAccessControl(${ '$' }sshDirectory)
                    ${'$'}directoryAcl.SetAccessRuleProtection(${ '$' }true, ${ '$' }false)
                    ${'$'}directoryAcl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(${ '$' }identity.User, ${ '$' }fullControl, ${ '$' }inheritance, ${ '$' }propagation, ${ '$' }allow)))
                    ${'$'}directoryAcl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(${ '$' }systemSid, ${ '$' }fullControl, ${ '$' }inheritance, ${ '$' }propagation, ${ '$' }allow)))
                    [IO.Directory]::SetAccessControl(${ '$' }sshDirectory, ${ '$' }directoryAcl)
                }
                ${'$'}stage = 'COMPLETE'
                if (${ '$' }exists) { ${'$'}result = 'EXISTS' } else { ${'$'}result = 'ADDED' }
                [Console]::Out.WriteLine('SUSHIERIC_RESULT:' + ${'$'}result)
                [Console]::Out.WriteLine('SUSHIERIC_STAGE:' + ${'$'}stage)
                [Console]::Out.WriteLine('SUSHIERIC_TARGET:' + ${'$'}target)
                [Console]::Out.WriteLine('SUSHIERIC_PATH:' + ${'$'}authorizedKeys)
                [Console]::Out.WriteLine('SUSHIERIC_KEY_STATE:' + ${'$'}keyState)
            } catch {
                ${'$'}rootException = ${'$'}_.Exception
                while (${ '$' }rootException.InnerException) { ${'$'}rootException = ${'$'}rootException.InnerException }
                if (${ '$' }rootException.Message -eq 'INVALID_PUBLIC_KEY') {
                    ${'$'}errorCode = 'INVALID_PUBLIC_KEY'
                } elseif (${ '$' }rootException -is [UnauthorizedAccessException]) {
                    ${'$'}errorCode = 'ACCESS_DENIED'
                } else {
                    ${'$'}errorCode = 'REMOTE_OPERATION_FAILED'
                }
                [Console]::Error.WriteLine('SUSHIERIC_ERROR:' + ${'$'}errorCode)
                [Console]::Error.WriteLine('SUSHIERIC_STAGE:' + ${'$'}stage)
                [Console]::Error.WriteLine('SUSHIERIC_TARGET:' + ${'$'}target)
                [Console]::Error.WriteLine('SUSHIERIC_PATH:' + ${'$'}authorizedKeys)
                [Console]::Error.WriteLine('SUSHIERIC_KEY_STATE:' + ${'$'}keyState)
                exit 1
            }
        """.trimIndent()
    }
}
