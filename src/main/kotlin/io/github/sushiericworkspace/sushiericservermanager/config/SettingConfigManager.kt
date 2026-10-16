package io.github.sushiericworkspace.sushiericservermanager.config

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File

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
    val consoleLogLevel: String = DEFAULT_CONSOLE_LOG_LEVEL
) {
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

        val repaired =
            GeneratedKeyPathRepair.repair(
                config = loaded,
                legacyDirectory =
                    File(
                        OS.dataConfigBase,
                        ConfigDirectoryMigration.LEGACY_DIRECTORY_NAME
                    ),
                currentDirectory =
                    File(OS.dataConfigBase, FilePath.DIRECTORY_NAME)
            )

        if (repaired == loaded) {
            return loaded
        }

        logger.info("生成鍵のパスを新しい設定ディレクトリへ更新しました。")
        save(repaired)

        return repaired
    }

    /** 保存後に再読み込みし、書き込んだ内容と一致することを確認します。 */
    fun saveAndVerify(config: ServerConfig): Boolean {
        save(config)
        return load() == config
    }
}
