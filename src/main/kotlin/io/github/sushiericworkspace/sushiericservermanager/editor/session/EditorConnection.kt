package io.github.sushiericworkspace.sushiericservermanager.editor.session

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility

/**
 * SSH以外の編集接続のライフサイクルです。openとcloseはUIスレッド外で実行します。
 * openまたはcloseの失敗時もEditorSessionが保持し、別接続への切替を禁止します。
 */
interface EditorConnection {
    val contextName: String
    val compatibility: ManagerCompatibility
    fun open(): EditorDataService
    fun close()
    /** アプリ終了時に不確定な接続を保全します。完了した切断を推測して申告しません。 */
    fun abort()
}
