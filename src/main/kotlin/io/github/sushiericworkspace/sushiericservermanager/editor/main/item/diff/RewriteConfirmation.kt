package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.diff

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.util.Utility.applyCommonStyle
import io.github.sushiericworkspace.sushiericservermanager.util.Utility.createScene
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBoxTreeItem
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.scene.control.TreeView
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import org.slf4j.LoggerFactory

class RewriteConfirmation(
    private val originalData: MutableItemBaseData,
    private val serverData: MutableItemBaseData
) : javafx.stage.Stage() {

    private val logger = LoggerFactory.getLogger(javaClass)

    // 呼び出し側が「最終的にどう決断したか」を受け取るためのフラグ
    var isConfirmed: Boolean = false
        private set

    // 💡 【型安全化】ユーザーが選択した（チェックを入れた）DiffIdを外部に公開するためのプロパティ
    val selectedCheckedFields = mutableSetOf<ItemDiffId>()

    private var forceSaveButton: Button
    private var diffTreeView: TreeView<ItemDiffId?>

    init {
        // --- 1. ウィンドウ（Stage）の基本設定 ---
        title = "サーバー上のデータが変更されました"
        initModality(Modality.APPLICATION_MODAL) // 後ろの画面を操作不可にする
        isResizable = true // ツリーが見やすいようにサイズ変更を許可

        // --- 2. 差分ツリーの即時組み立て ---
        val treeBuilder = ItemDiffTreeBuilder()
        diffTreeView = treeBuilder.buildDiffTree(originalData, serverData).apply {
            styleClass.add("diff-tree-view")
        }
        @Suppress("UNCHECKED_CAST")
        val rootItem = diffTreeView.root as CheckBoxTreeItem<ItemDiffId?>

        // 上書き保存ボタン
        forceSaveButton = Button("上書き保存").apply {
            isFocusTraversable = false
            styleClass.add("btn-danger")

            setOnAction {
                // 💡 1. 現在チェックされている DiffId のセットを回収
                val currentChecked = treeBuilder.collectCheckedFields(rootItem)

                // 💡 2. すべての差分DiffIdをその場で再帰的に集めるローカル関数
                val allFields = mutableSetOf<ItemDiffId>()

                fun collectAll(item: CheckBoxTreeItem<*>) {
                    val value = item.value
                    // 末端のノード（子を持たない）かつ DiffId が保持されているものだけを集める
                    if (value is ItemDiffId && item.children.isEmpty()) {
                        allFields.add(value)
                    }

                    // 子要素をループ
                    for (child in item.children) {
                        // 💡 スタープロジェクションでチェックし、安全に再帰呼び出し
                        if (child is CheckBoxTreeItem<*>) {
                            collectAll(child)
                        }
                    }
                }

                // 💡 3. 関数を呼び出して実行
                collectAll(rootItem)

                // チェックが入っていない（＝破棄してサーバー側に合わせる）DiffId を抽出
                val uncheckedFields = allFields.filter { it !in currentChecked }

                // 3. 確認メッセージのテキストを構築
                val messageFlow = TextFlow().apply {
                    lineSpacing = 4.0
                    style = "-fx-background-color: #252538; -fx-padding: 15px; -fx-background-radius: 5px;"
                }

                // アイテムの内部値から表示用文言を安全に引くためのローカル関数
                fun getDiffText(id: ItemDiffId): String {
                    // ItemDiffTreeBuilder 内の getDisplayString と共通化した private 関数（または同一ロジック）を呼び出す
                    // ここではリフレクション等を用いず、安全にBuilder側のフォーマットに合わせるため
                    // ダイアログ専用に簡易版の文字列を再構築、あるいはBuilderのメソッドをパブリックにして呼び出してもOK
                    return when (id.field) {
                        ItemDiffField.RARITY -> "レアリティ: ${originalData.rarity.name} ➔ ${serverData.rarity.name}"
                        ItemDiffField.DISPLAY_NAME -> "表示名: \"${originalData.display.displayName}\" ➔ \"${serverData.display.displayName}\""
                        ItemDiffField.LORE -> "Lore [${(id.index ?: 0) + 1}行目]"
                        ItemDiffField.STATS -> id.statsType?.name ?: "ステータス"
                        ItemDiffField.STAT_MULTIPLIER -> "${id.statsType?.name ?: "ステータス"} 倍率"
                        ItemDiffField.COMMENT -> "説明文 [${(id.index ?: 0) + 1}行目]"
                        ItemDiffField.HEAD_SKIN -> "ヘッドスキン"
                        ItemDiffField.DETAIL -> "詳細データ"
                    }
                }

                messageFlow.children.apply {
                    // 導入文
                    add(Text("保存しますか？未選択項目はサーバー側に合わせて上書きされます。\n\n").apply {
                        style = "-fx-fill: #ffffff; -fx-font-size: 13px;"
                    })

                    // --- 上書きセクション ---
                    add(Text("【上書きする項目（ローカルを優先）】\n").apply {
                        style = "-fx-fill: #ff4d4d; -fx-font-weight: bold; -fx-font-size: 13px;"
                    })
                    if (currentChecked.isEmpty()) {
                        add(Text(" ・ （なし）\n").apply { style = "-fx-fill: #8b8d99;" })
                    } else {
                        currentChecked.forEach { field ->
                            add(Text(" ・ ${getDiffText(field)}\n").apply { style = "-fx-fill: #ffccd5; -fx-font-family: 'Consolas';" })
                        }
                    }

                    // --- 破棄セクション ---
                    add(Text("\n【破棄する項目（サーバー側を維持）】\n").apply {
                        style = "-fx-fill: #4ea8de; -fx-font-weight: bold; -fx-font-size: 13px;"
                    })
                    if (uncheckedFields.isEmpty()) {
                        add(Text(" ・ （なし）\n").apply { style = "-fx-fill: #8b8d99;" })
                    } else {
                        uncheckedFields.forEach { field ->
                            add(Text(" ・ ${getDiffText(field)}\n").apply { style = "-fx-fill: #a2d2ff; -fx-font-family: 'Consolas';" })
                        }
                    }
                }

                // ② スクロールペインで包む
                val scrollPane = ScrollPane(messageFlow).apply {
                    isFitToWidth = true
                    prefHeight = 250.0
                    hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                    vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
                    style = "-fx-background: transparent; -fx-background-color: transparent; -fx-viewport-background: transparent;"
                }

                // 4. JavaFXの確認ダイアログを表示
                val alert = Alert(Alert.AlertType.CONFIRMATION).apply {
                    title = "最終確認"
                    headerText = "本当に変更を適用して保存しますか？"
                    dialogPane.content = scrollPane
                    buttonTypes.setAll(ButtonType.YES, ButtonType.NO)
                    initOwner(this@RewriteConfirmation)
                    applyCommonStyle()
                }

                // 5. ユーザーの選択結果で分岐
                val result = alert.showAndWait()
                if (result.isPresent && result.get() == ButtonType.YES) {
                    // 💡 「はい」が押された時、StringではなくDiffIdのオブジェクトを確定させて閉じる
                    selectedCheckedFields.clear()
                    selectedCheckedFields.addAll(currentChecked)

                    isConfirmed = true
                    close()
                } else {
                    logger.info("ユーザーが最終保存確認をキャンセルしました。")
                }
            }
        }

        // --- 3. 全体レイアウトの構築 ---
        val rootLayout = VBox(15.0).apply {
            styleClass.add("rewrite-confirmation-root")

            padding = Insets(20.0)
            prefWidth = 550.0
            prefHeight = 650.0
            alignment = Pos.TOP_LEFT

            children.addAll(
                Label("警告: リモートサーバー上のデータが更新されています！\n(対象アイテム: ${originalData.id})").apply {
                    style = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #d90429;"
                    isWrapText = true
                },
                Label("サーバー上のデータを上書きして、現在の編集内容で保存しますか？\n上書きする項目にチェックを入れてください。").apply {
                    isWrapText = true
                },
                diffTreeView.apply {
                    VBox.setVgrow(this, Priority.ALWAYS)
                },
                HBox(10.0).apply {
                    alignment = Pos.CENTER_RIGHT
                    children.addAll(
                        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                        Button("キャンセル").apply {
                            isFocusTraversable = false
                            styleClass.add("btn-cancel")
                            setOnAction {
                                isConfirmed = false
                                close()
                            }
                        },
                        forceSaveButton
                    )
                }
            )
        }

        // --- 4. シーンの設定と適用 ---
        this.scene = createScene(
            screen = AppScreen.WIDGETS_ONLY,
            customRoot = rootLayout
        )
    }
}
