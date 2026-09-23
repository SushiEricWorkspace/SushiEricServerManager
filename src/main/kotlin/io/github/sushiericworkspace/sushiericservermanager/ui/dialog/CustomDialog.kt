package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.util.Utility.applyCommonStyle
import io.github.sushiericworkspace.sushiericservermanager.util.toCssHex
import javafx.application.Platform
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Modality
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.FutureTask

/**
 * アプリケーション全体のダイアログ表示を管理・ビルドする型安全なクラス。
 * Fluent API (メソッドチェーン) スタイルで直感的にダイアログを構築できます。
 */
class CustomDialog private constructor(private val type: Alert.AlertType) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private var title: String = if (type == Alert.AlertType.ERROR) "システムエラー" else "確認"
    private var header: String? = if (type == Alert.AlertType.ERROR) "予期しないエラーが発生しました" else "処理を続行しますか？"
    private var contentText: String = ""
    private var scrollableContent: Boolean = false
    private var owner: Stage? = null

    // 例外用
    private var throwable: Throwable? = null
    private var logLines: List<String> = emptyList()

    private var okText: String = "OK"
    private var cancelText: String = "キャンセル"
    private var okColorHex: String? = null
    private var cancelColorHex: String? = null

    companion object {
        /** エラーダイアログのビルダーを開始します */
        fun error() = CustomDialog(Alert.AlertType.ERROR)

        /**
         * エラーの種類（ErrorType）を指定してビルダーを開始します。
         * タイトルとヘッダーは ErrorType に定義された標準の文言が自動セットされます。
         */
        fun error(errorType: ErrorType) = CustomDialog(Alert.AlertType.ERROR).apply {
            this.title = errorType.defaultTitle
            this.header = errorType.defaultHeader
        }

        /** 確認ダイアログのビルダーを開始します */
        fun confirmation() = CustomDialog(Alert.AlertType.CONFIRMATION)

        /** 情報ダイアログのビルダーを開始します */
        fun information() = CustomDialog(Alert.AlertType.INFORMATION)
    }

    fun title(title: String = "確認") = apply { this.title = title }
    fun header(header: String? = "処理を続行しますか？") = apply { this.header = header }
    fun owner(owner: Stage?) = apply { this.owner = owner }

    /**
     * 【型安全】単一の文字列を本文として設定します。
     * 「\n」による手動改行を含めることも可能です。
     *
     * @param content 本文文字列
     */
    fun content(content: String) = apply {
        this.contentText = content
    }

    /**
     * 【型安全】文字列のリストを本文として設定します。
     * 各要素は内部で自動的に改行コード（\n）で結合されます。
     *
     * @param contentLines 本文の各行を格納したリスト
     */
    fun content(contentLines: List<String>) = apply {
        this.contentText = contentLines.joinToString("\n")
    }

    /** 長い複数行本文を、折り返し可能なスクロール領域で表示します。 */
    fun scrollableContent() = apply {
        scrollableContent = true
    }

    /** 例外オブジェクトと自動ログを登録する。 */
    fun exception(e: Throwable?, logs: List<String> = emptyList()) = apply {
        this.throwable = e
        this.logLines = logs
    }

    fun okButton(text: String = "OK", colorHex: String? = null) =
        configureButton(isOk = true, text = text, colorHex = colorHex)

    fun okButton(text: String = "OK", color: Color) =
        configureButton(isOk = true, text = text, colorHex = color.toCssHex())

    fun cancelButton(text: String = "キャンセル", colorHex: String? = null) =
        configureButton(isOk = false, text = text, colorHex = colorHex)

    fun cancelButton(text: String = "キャンセル", color: Color) =
        configureButton(isOk = false, text = text, colorHex = color.toCssHex())

    private fun configureButton(isOk: Boolean, text: String, colorHex: String?): CustomDialog = apply {
        if (isOk) {
            okText = text
            if (colorHex != null) okColorHex = colorHex
        } else {
            cancelText = text
            if (colorHex != null) cancelColorHex = colorHex
        }
    }

    // --- 実行メソッド ---

    /**
     * ダイアログを画面に表示します。
     * [Alert.AlertType.CONFIRMATION] の場合は OK が押されたら true、それ以外は常に false (またはエラー時は false) を返します。
     */
    fun show(): Boolean {
        if (type == Alert.AlertType.ERROR) {
            logLines.forEach { logger.error(it) }
            if (throwable != null) {
                logger.error("$title: ${throwable!!.message}", throwable)
            } else {
                logger.error("$title: $header - $contentText")
            }
        }

        val task = FutureTask {
            val alert = Alert(type).apply {
                this.title = this@CustomDialog.title
                this.headerText = this@CustomDialog.header

                // 例外のメッセージがあれば最優先、なければ contentText
                this.contentText = throwable?.localizedMessage ?: this@CustomDialog.contentText.ifEmpty { "詳細不明なエラーです。" }

                if (this@CustomDialog.owner != null) {
                    initOwner(this@CustomDialog.owner)
                    initModality(Modality.WINDOW_MODAL)
                } else {
                    initModality(Modality.APPLICATION_MODAL)
                }

                // テーマの適用
                applyCommonStyle()

                if (scrollableContent && throwable == null) {
                    dialogPane.content = TextArea(this@CustomDialog.contentText).apply {
                        styleClass.add("dialog-scrollable-content")
                        isEditable = false
                        isWrapText = true
                        prefColumnCount = 72
                        prefRowCount = 18
                    }
                    dialogPane.prefWidth = 760.0
                }

                // --- エラー用: スタックトレースの展開 ---
                if (type == Alert.AlertType.ERROR && throwable != null) {
                    val sw = StringWriter()
                    throwable!!.printStackTrace(PrintWriter(sw))
                    val textArea = TextArea(sw.toString()).apply {
                        isEditable = false
                        isWrapText = true
                    }
                    dialogPane.expandableContent = VBox(Label("スタックトレース:"), textArea)
                }

                if (type == Alert.AlertType.CONFIRMATION) {
                    val okButtonType = ButtonType(okText, ButtonBar.ButtonData.OK_DONE)
                    val cancelButtonType = ButtonType(cancelText, ButtonBar.ButtonData.CANCEL_CLOSE)
                    buttonTypes.setAll(okButtonType, cancelButtonType)

                    val okButton = dialogPane.lookupButton(okButtonType) as Button
                    val cancelButton = dialogPane.lookupButton(cancelButtonType) as Button

                    okButton.applyDialogButtonStyle(
                        colorHex = okColorHex,
                        defaultStyleClass = "btn-primary"
                    )
                    cancelButton.applyDialogButtonStyle(
                        colorHex = cancelColorHex,
                        defaultStyleClass = "btn-cancel"
                    )
                    DialogButtonRoles.apply(okButton, cancelButton)
                }
            }

            val result = alert.showAndWait()
            result.isPresent && result.get().buttonData == ButtonBar.ButtonData.OK_DONE
        }

        if (Platform.isFxApplicationThread()) {
            task.run()
        } else {
            Platform.runLater(task)
        }

        return try {
            task.get()
        } catch (e: Exception) {
            logger.error("ダイアログ表示中に致命的なエラーが発生しました", e)
            false
        }
    }

    private fun Button.applyDialogButtonStyle(colorHex: String?, defaultStyleClass: String) {
        when (colorHex?.uppercase()) {
            null -> styleClass.add(defaultStyleClass)
            Color.RED.toCssHex() -> styleClass.add("btn-danger")
            else -> {
                styleClass.add("dialog-action-custom")
                style = "-fx-background-color: $colorHex;"
            }
        }
    }
}
