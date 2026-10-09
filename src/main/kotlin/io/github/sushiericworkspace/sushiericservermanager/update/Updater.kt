package io.github.sushiericworkspace.sushiericservermanager.update

import io.github.sushiericworkspace.sushiericservermanager.config.OS
import java.io.File
import java.io.IOException

/**
 * ダウンロードして検証した成果物で、Managerを置換して再起動する外部のプロセスを起動します。
 *
 * 起動したプロセスは、Managerが終了するのを待って置換するため、
 * [start]を呼んだ側は、続けてアプリを終了してください。
 */
interface Updater {
    /**
     * 更新用のプロセスを起動します。
     *
     * @param installer 検証済みのインストーラー。
     * @param version 更新後の版。結果の表示に使います。
     * @throws IOException プロセスを起動できない場合。
     */
    @Throws(IOException::class)
    fun start(installer: File, version: String)
}

/** 実行中のOSとインストール状態に合うUpdaterを返します。 */
object Updaters {
    /**
     * @return 利用できるUpdater。対応するOSでない場合と、インストールされたアプリとして起動していない
     * 場合（開発中の実行など）はnull。
     */
    fun forCurrentOs(): Updater? = when {
        OS.isWindows -> WindowsUpdater.forInstalledApp()
        OS.isMac -> MacUpdater.forInstalledApp()
        else -> null
    }
}
