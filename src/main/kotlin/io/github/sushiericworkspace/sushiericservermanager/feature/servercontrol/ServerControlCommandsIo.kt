package io.github.sushiericworkspace.sushiericservermanager.feature.servercontrol

import io.github.sushiericworkspace.sushiericservermanager.config.ServerControlCommandSet
import io.github.sushiericworkspace.sushiericservermanager.config.ServerControlCommandsExport
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * サーバー操作コマンドの受け渡し用ファイルを読み書きします。
 *
 * 書き出すファイルにはプロファイル名やホストを含めません。
 * 適用先は受け取った側が決めるため、接続情報を渡さずに定義だけを共有できます。
 */
internal object ServerControlCommandsIo {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * コマンドの組をファイルへ書き出します。
     *
     * @return 書き出せた場合は`true`。
     */
    fun export(
        file: File,
        commandSet: ServerControlCommandSet
    ): Boolean =
        try {
            file.parentFile?.mkdirs()

            file.writeText(
                json.encodeToString(
                    ServerControlCommandsExport.serializer(),
                    ServerControlCommandsExport.from(commandSet)
                )
            )

            true
        } catch (exception: Exception) {
            logger.warn("サーバー操作コマンドを書き出せませんでした: {}", file, exception)
            false
        }

    /**
     * ファイルからコマンドの組を読み込みます。
     *
     * 形式の版が想定と異なる場合と、解析できない場合は読み込みません。
     *
     * @return 読み込んだコマンドの組。読み込めない場合は`null`。
     */
    fun import(file: File): ServerControlCommandSet? {
        val exported =
            try {
                json.decodeFromString(
                    ServerControlCommandsExport.serializer(),
                    file.readText()
                )
            } catch (exception: Exception) {
                logger.warn("サーバー操作コマンドを読み込めませんでした: {}", file, exception)
                return null
            }

        if (!ServerControlCommandsExport.supports(exported.formatVersion)) {
            logger.warn(
                "対応していない形式のファイルです: formatVersion={}",
                exported.formatVersion
            )
            return null
        }

        return exported.toCommandSet()
    }
}
