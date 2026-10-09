package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.update.AppVersion
import io.github.sushiericworkspace.sushiericservermanager.update.ChecksumMismatchException
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateCheckResult
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateDownloader
import io.github.sushiericworkspace.sushiericservermanager.update.Updater
import io.github.sushiericworkspace.sushiericservermanager.update.Updaters
import javafx.application.Platform
import javafx.concurrent.Task
import javafx.concurrent.WorkerStateEvent
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.TextArea
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CancellationException

/**
 * 更新が見つかったことを知らせ、自動で更新できる場合は成果物のダウンロードと検証を進捗つきで行います。
 *
 * 検証が終わったあと、Updaterを使える環境では「再起動して更新」を表示します。押すとUpdaterを起動し、
 * 呼び出し側にアプリの終了を求めます（[Outcome.EXIT]）。使えない環境では、そのまま起動を続けます。
 * JavaFX Application Threadから呼び出してください。
 */
object UpdateDialog {
    private val logger = LoggerFactory.getLogger(UpdateDialog::class.java)

    /** ダイアログを閉じたあとに、呼び出し側が行うことです。 */
    enum class Outcome {
        /** 通常の起動を続ける。 */
        CONTINUE,

        /** Updaterを起動したため、アプリを終了する。 */
        EXIT
    }

    /**
     * 更新のダイアログを表示し、閉じられるまで待ちます。
     *
     * @param openUrl ダウンロードページをブラウザーで開く関数。
     * @param updater 置換と再起動を行うUpdater。nullの場合は「再起動して更新」を表示しません。
     */
    fun show(
        update: UpdateCheckResult.Update,
        openUrl: (String) -> Unit,
        updater: Updater? = Updaters.forCurrentOs()
    ): Outcome {
        var outcome = Outcome.CONTINUE
        val stage = Stage().apply {
            title = "アップデート確認"
            initModality(Modality.APPLICATION_MODAL)
            isResizable = false
        }

        val notes = TextArea(update.notes.ifBlank { "変更内容は記載されていません。" }).apply {
            isEditable = false
            isWrapText = true
            prefRowCount = 8
            prefColumnCount = 48
        }
        val status = Label()
        val progress = ProgressBar(0.0).apply {
            maxWidth = Double.MAX_VALUE
            isVisible = false
            isManaged = false
        }
        val primary = Button()
        val secondary = Button("閉じる")
        val releaseLink = Hyperlink("リリースページを開く").apply { setOnAction { openUrl(update.releaseUrl) } }
        val buttons = HBox(10.0, releaseLink, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, primary, secondary)
            .apply { alignment = Pos.CENTER_RIGHT }

        val root = VBox(
            10.0,
            Label("新しいバージョンがあります。"),
            Label("現在のバージョン: ${AppVersion.CURRENT}　最新バージョン: ${update.version}"),
            notes,
            status,
            progress,
            buttons
        ).apply {
            styleClass.add("common-root")
            padding = Insets(16.0)
        }

        secondary.setOnAction { stage.close() }
        when (update) {
            is UpdateCheckResult.Manual -> {
                status.text = update.reason + "\nリリースページから、手動で更新してください。"
                primary.isVisible = false
                primary.isManaged = false
            }
            is UpdateCheckResult.Automatic -> {
                status.text = "更新をダウンロードして、SHA-256を検証します。"
                primary.text = "ダウンロード"
                primary.setOnAction {
                    startDownload(stage, update, updater, status, progress, primary, secondary) {
                        outcome = Outcome.EXIT
                    }
                }
            }
        }

        stage.scene = Scene(root).apply {
            UpdateDialog::class.java.getResource(AppScreen.WIDGETS_ONLY.css)?.toExternalForm()?.let(stylesheets::add)
        }
        stage.showAndWait()
        return outcome
    }

    private fun startDownload(
        stage: Stage,
        update: UpdateCheckResult.Automatic,
        updater: Updater?,
        status: Label,
        progress: ProgressBar,
        primary: Button,
        secondary: Button,
        onRestart: () -> Unit
    ) {
        val downloader = UpdateDownloader(FilePath.UPDATES_DIR.toFile())
        var cancelled = false
        val task = object : Task<File>() {
            override fun call(): File = downloader.download(
                update,
                onProgress = { received, total ->
                    if (total > 0) Platform.runLater {
                        progress.progress = received.toDouble() / total
                        status.text = "ダウンロード中... ${megabytes(received)} / ${megabytes(total)}"
                    }
                },
                isCancelled = { cancelled }
            ).also { Platform.runLater { status.text = "SHA-256を検証しました。" } }
        }

        progress.isVisible = true
        progress.isManaged = true
        progress.progress = ProgressBar.INDETERMINATE_PROGRESS
        status.text = "ダウンロードを開始しています..."
        primary.isDisable = true
        secondary.text = "中止"
        secondary.setOnAction { cancelled = true }
        stage.setOnCloseRequest { it.consume() }

        fun finish(message: String, progressValue: Double) {
            progress.progress = progressValue
            status.text = message
            primary.isVisible = false
            primary.isManaged = false
            secondary.text = "閉じる"
            secondary.setOnAction { stage.close() }
            stage.onCloseRequest = null
        }

        task.addEventHandler(WorkerStateEvent.WORKER_STATE_SUCCEEDED) {
            val installer = task.value
            if (updater == null) {
                finish(
                    "ダウンロードとSHA-256の検証が完了しました。\n保存先: $installer\n" +
                        "この環境では更新を自動で適用できないため、今回はそのまま起動します。",
                    1.0
                )
                return@addEventHandler
            }
            finish(
                "ダウンロードとSHA-256の検証が完了しました。\n" +
                    "「再起動して更新」を押すと、Managerを終了して更新し、再起動します。設定やデータは引き継がれます。",
                1.0
            )
            primary.text = "再起動して更新"
            primary.isDisable = false
            primary.isVisible = true
            primary.isManaged = true
            primary.setOnAction {
                try {
                    updater.start(installer, update.version)
                    onRestart()
                    stage.close()
                } catch (e: Exception) {
                    logger.error("Updaterを起動できませんでした。", e)
                    status.text = "更新を開始できませんでした: ${e.message ?: "原因不明"}"
                    primary.isDisable = true
                }
            }
        }
        task.addEventHandler(WorkerStateEvent.WORKER_STATE_FAILED) {
            val error = task.exception
            logger.warn("更新のダウンロードに失敗しました。", error)
            val message = when (error) {
                is CancellationException -> "ダウンロードを中止しました。"
                is ChecksumMismatchException ->
                    "ダウンロードしたファイルのSHA-256が一致しなかったため、削除して更新を中止しました。"
                else -> "ダウンロードに失敗しました: ${error?.message ?: "原因不明"}"
            }
            finish(message, 0.0)
        }
        Thread(task, "update-download").apply { isDaemon = true; start() }
    }

    private fun megabytes(bytes: Long): String = "%.1fMB".format(bytes / 1024.0 / 1024.0)
}
