package io.github.sushiericworkspace.sushiericservermanager.communication.management

import java.time.Duration

/**
 * 接続先と送信経路を持つManagement APIの通信境界です。
 * 接続・切断は呼び出しスレッドで待機し、受信はlistenerへ通知します。
 * 切断の完了を確認できない場合は例外を投げ、呼び出し側が接続を保持します。
 */
interface ManagementTransport {
    /** 受信通知先を設定して接続し、接続先のローカルポートを返します。失敗時は例外を投げます。 */
    fun connect(listener: ServerManagementListener, timeout: Duration): Int
    /** 新規要求を受理できた場合はtrueを返します。サーバー側の処理成功を意味しません。 */
    fun send(request: ServerManagementRequest): Boolean
    /** 受理済み要求と接続の終了を待ちます。終了未確認の場合は例外を投げます。 */
    fun disconnect()
}
