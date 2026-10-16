package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * アプリケーションの多重起動を防止するためのロック管理オブジェクト。
 *
 * [FilePath.LOCK] 配下に `app.lock` を作成し、[FileLock] を取得することで、
 * 同じユーザー環境でアプリが複数起動されることを防ぎます。
 *
 * ロックはアプリケーション実行中ずっと保持する必要があるため、
 * [channel] と [lock] はプロパティとして保持します。
 */
object SingleAppLock {

    /** ロックファイルを開いているファイルチャンネル。 */
    private var channel: FileChannel? = null

    /** 現在保持しているファイルロック。 */
    private var lock: FileLock? = null

    /**
     * アプリケーションのロック取得を試みます。
     *
     * ロック用ディレクトリが存在しない場合は作成し、
     * `app.lock` に対して排他ロックを取得します。
     *
     * @return ロックを取得できた場合は `true`、すでに他のプロセスがロックしている場合や、
     *         ファイル操作に失敗した場合は `false`
     */
    fun tryLock(): Boolean {
        return try {
            val lockDir = FilePath.LOCK.toFile()
            lockDir.mkdirs()

            val lockFile = lockDir.resolve("app.lock")

            channel = RandomAccessFile(lockFile, "rw").channel
            lock = channel?.tryLock()

            if (lock == null) {
                closeInternal()
                false
            } else {
                true
            }
        } catch (e: java.nio.channels.OverlappingFileLockException) {
            e.printStackTrace()
            closeInternal()
            false
        } catch (e: java.io.IOException) {
            e.printStackTrace()
            closeInternal()
            false
        } catch (e: SecurityException) {
            e.printStackTrace()
            closeInternal()
            false
        }
    }

    /**
     * 現在保持しているロックを解除します。
     *
     * アプリケーション終了時に呼び出し、ロックファイルのロック解除と
     * ファイルチャンネルのクローズを行います。
     */
    fun release() {
        closeInternal()
    }

    /**
     * ロック解除とファイルチャンネルのクローズを行う内部処理。
     *
     * [tryLock] の途中で失敗した場合と、[release] による通常終了時の両方で使用します。
     * 例外が発生しても終了処理を続行できるように、各処理は [runCatching] で包みます。
     */
    private fun closeInternal() {
        runCatching {
            lock?.release()
        }

        runCatching {
            channel?.close()
        }

        lock = null
        channel = null
    }
}