package io.github.sushiericworkspace.sushiericservermanager.communication

data class RemoteResource(
    /** ファイル名(拡張子込み) */
    val name: String,
    /** サーバー上の絶対パス */
    val remotePath: String
)