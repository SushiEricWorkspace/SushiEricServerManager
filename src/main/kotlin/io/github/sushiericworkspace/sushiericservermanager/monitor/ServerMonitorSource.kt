package io.github.sushiericworkspace.sushiericservermanager.monitor

/**
 * 監視情報の取得元です。
 *
 * 取得元ごとに扱う情報の範囲が異なるため、型として区別します。
 * 画面へ表示する際も、どこから得た値かを利用者が判断できるようにします。
 */
enum class ServerMonitorSource {

    /**
     * Management API経由でMinecraftサーバーから取得します。
     *
     * TPS、MSPT、プレイヤー数、JVMの状態が該当します。
     */
    MANAGEMENT_API,

    /**
     * SSH経由でホストOSから取得します。
     *
     * CPU使用率、システムメモリ、ディスク使用量が該当します。
     * 取得処理は本Issueの対象外であり、経路の区別だけを定義します。
     */
    SSH
}
