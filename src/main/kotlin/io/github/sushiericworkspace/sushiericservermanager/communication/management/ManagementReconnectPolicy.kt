package io.github.sushiericworkspace.sushiericservermanager.communication.management

/**
 * Management APIへ自動で接続し直す間隔と、打ち切りの判断を保持します。
 *
 * 切断直後は短い間隔で試し、回数を重ねるごとに間隔を広げます。
 * 用意した間隔を使い切った時点で自動の再試行を打ち切り、以降は手動の再接続へ委ねます。
 * サーバーが停止したままの場合に、再試行を延々と続けないためです。
 *
 * 試行回数を保持するため、1つの接続に対して1つのインスタンスを使用します。
 *
 * @param delaysSeconds 1回目から順に使用する待ち時間の一覧。
 */
internal class ManagementReconnectPolicy(
    private val delaysSeconds: List<Long> = DEFAULT_DELAYS_SECONDS
) {
    init {
        require(delaysSeconds.isNotEmpty()) {
            "再試行の間隔を1つ以上指定してください。"
        }
        require(delaysSeconds.all { it > 0 }) {
            "再試行の間隔は正の値である必要があります。値: $delaysSeconds"
        }
    }

    private var attempt = 0

    /** これまでに払い出した再試行の回数です。 */
    val attemptCount: Int
        get() = attempt

    /** 自動で再試行する上限回数です。 */
    val maxAttempts: Int
        get() = delaysSeconds.size

    /**
     * 次の再試行までの待ち時間を秒で返します。
     *
     * 呼び出すごとに次の間隔へ進みます。
     *
     * @return 待ち時間。上限へ達している場合は`null`。
     */
    fun nextDelaySeconds(): Long? {
        val delay =
            delaysSeconds.getOrNull(attempt)
                ?: return null

        attempt++
        return delay
    }

    /** 再試行の回数を初期状態へ戻します。 */
    fun reset() {
        attempt = 0
    }

    companion object {
        /**
         * 既定の再試行間隔です。
         *
         * 合計でおよそ5分を再試行に充てます。
         * Minecraftサーバーの再起動には数分かかることがあり、
         * 短い時間で打ち切ると再起動へ追随できないためです。
         *
         * 切断直後は短い間隔で試し、以降は60秒間隔にして負荷を抑えます。
         */
        val DEFAULT_DELAYS_SECONDS: List<Long> =
            listOf(3, 5, 10, 20, 30, 60, 60, 60, 60)
    }
}
