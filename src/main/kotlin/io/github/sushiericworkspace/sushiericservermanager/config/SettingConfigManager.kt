package io.github.sushiericworkspace.sushiericservermanager.config

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ServerConfig(
    val list: List<ServerProfile> = emptyList()
)

/**
 * 個別のサーバー接続情報。
 *
 * 従来のname、host、port、user、path、keyは変更しません。
 * 追加項目はすべてデフォルト値を持つため、既存のprofiles.jsonをそのまま読み込めます。
 */
@Serializable
data class ServerProfile(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val path: String,
    val key: String,
    val authenticationType: String = AuthenticationType.EXISTING_PRIVATE_KEY.storedValue,
    val generatedKey: Boolean = false,
    val keyFormat: String? = null,
    val remoteOperatingSystem: String = RemoteOperatingSystem.UBUNTU_SERVER.storedValue,

    /**
     * サーバー側のManagement APIポート。
     *
     * SSH Tunnelの転送先ポートとして使用します。転送先ホストは
     * サーバーのループバックへ固定されており、設定では変更できません。
     */
    val managementPort: Int = DEFAULT_MANAGEMENT_PORT,

    /**
     * コンソールへ表示するログレベルの下限です。
     *
     * 画面で選んだ値を保持し、次にコンソールを開いたときへ引き継ぎます。
     * レベルの解釈は表示側が行うため、ここでは文字列のまま保持します。
     */
    val consoleLogLevel: String = DEFAULT_CONSOLE_LOG_LEVEL,

    /** ローカル文書の保存元を識別します。既存profileは読み込み時に採番・保存します。 */
    val profileId: String = ""
) {
    /** 名前・鍵・接続runを含めず、確定したprofileと接続先/rootへ文書を束縛します。 */
    fun documentSource(): EditorDocumentSource.Server {
        check(profileId.isNotEmpty()) { "文書を開く前にprofile識別子を保存してください。" }
        return EditorDocumentSource.Server(profileId, listOf(host.lowercase(), port.toString(), user, path).joinToString("\u0000"))
    }

    fun resolvedAuthenticationType(): AuthenticationType =
        AuthenticationType.fromStoredValue(authenticationType)

    fun resolvedRemoteOperatingSystem(): RemoteOperatingSystem =
        RemoteOperatingSystem.fromStoredValue(remoteOperatingSystem)

    /**
     * 指定できる範囲へ丸めたManagement APIポートを返します。
     *
     * 範囲外の値が保存されていても、そのまま接続へ使わないようにします。
     */
    fun resolvedManagementPort(): Int =
        managementPort.coerceIn(MANAGEMENT_PORT_RANGE)

    companion object {
        /** Management APIポートの既定値です。SushiEricServerModの既定値と一致させます。 */
        const val DEFAULT_MANAGEMENT_PORT: Int = 25580

        /** 指定できるManagement APIポートの範囲です。 */
        val MANAGEMENT_PORT_RANGE: IntRange = 1..65535

        /** コンソールへ表示するログレベルの下限の既定値です。 */
        const val DEFAULT_CONSOLE_LOG_LEVEL: String = "INFO"
    }
}

object SettingConfigManager : JsonFileHandler<ServerConfig>(
    FilePath.SERVER_PROFILES,
    { ServerConfig() },
    ServerConfig.serializer()
) {
    private val logger = LoggerFactory.getLogger(SettingConfigManager::class.java)

    /**
     * プロファイルを読み込みます。
     *
     * 設定ディレクトリの移行で取り残された生成鍵のパスがあれば、
     * 新しいディレクトリへ向け直して保存します。
     *
     * 移行時ではなく読み込み時に行うのは、移行が既に済んでいる環境にも
     * 旧パスが残っているためです。
     */
    fun loadRepaired(): ServerConfig {
        val loaded = load()
        val identified = assignProfileIds(loaded)
        if (identified != loaded) {
            val original = FilePath.SERVER_PROFILES.toFile()
            if (original.isFile) Files.copy(original.toPath(),
                original.parentFile.resolve(original.name + ".before-document-ids-" + UUID.randomUUID()).toPath())
            check(saveAndVerify(identified)) { "サーバー設定の識別子を保存できません。元設定を保持しました。" }
        }

        val repaired =
            GeneratedKeyPathRepair.repair(
                config = identified,
                legacyDirectory =
                    File(
                        OS.dataConfigBase,
                        ConfigDirectoryMigration.LEGACY_DIRECTORY_NAME
                    ),
                currentDirectory =
                    File(OS.dataConfigBase, FilePath.DIRECTORY_NAME)
            )

        if (repaired == identified) {
            return identified
        }

        logger.info("生成鍵のパスを新しい設定ディレクトリへ更新しました。")
        check(saveAndVerify(repaired)) { "サーバー設定を保存できません。" }

        return repaired
    }

    /** 保存後に再読み込みし、書き込んだ内容と一致することを確認します。 */
    fun saveAndVerify(config: ServerConfig): Boolean {
        val file = FilePath.SERVER_PROFILES.toFile()
        var temporary: File? = null
        return try {
            val identified = assignProfileIds(config)
            file.parentFile.mkdirs()
            temporary = Files.createTempFile(file.parentFile.toPath(), ".profiles-", ".json").toFile()
            temporary.writeText(Json { prettyPrint = true; encodeDefaults = true }.encodeToString(identified))
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            load() == identified
        } catch (error: Exception) {
            logger.error("サーバー設定を保存できませんでした。", error)
            false
        } finally {
            temporary?.delete()
        }
    }
}

/** 欠落IDだけを採番し、確定済みIDとprofileの順序を維持します。 */
internal fun assignProfileIds(config: ServerConfig): ServerConfig {
    val profiles = config.list.map { profile ->
        if (profile.profileId.isEmpty()) profile.copy(profileId = UUID.randomUUID().toString()) else profile
    }
    require(profiles.map { it.profileId }.toSet().size == profiles.size) { "サーバー設定の識別子が重複しています。" }
    return config.copy(list = profiles)
}
