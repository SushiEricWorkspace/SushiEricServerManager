package io.github.sushiericworkspace.sushiericservermanager.editor.tree

import javafx.scene.Node
import javafx.scene.control.ContextMenu
import javafx.scene.control.TreeItem

/**
 * エディタ用ツリーに表示する「行」の共通インターフェース。
 *
 * このインターフェースは、アイテムエディタ・スキルエディタ・クエストエディタなど、
 * 将来的に複数のエディタで TreeView を使い回すための最小共通構造を表します。
 *
 * ここでは具体的な行の種類、たとえば
 *
 * - アイテムの Lore 行
 * - ステータス行
 * - スキル設定行
 * - クエスト条件行
 *
 * などは定義しません。
 *
 * 具体的な行定義は各エディタ側で `sealed class` や `sealed interface` として実装します。
 *
 * 例:
 *
 * ```kotlin
 * sealed interface ItemTreeRow : EditorTreeRow {
 *     sealed class Folder(...) : ItemTreeRow
 *     sealed class Editor(...) : ItemTreeRow
 * }
 * ```
 *
 * 共通側が知る必要があるのは、
 *
 * - 表示名
 * - 行の種別
 * - その行が属する編集コンテキスト
 *
 * のみです。
 */
interface EditorTreeRow {

    /**
     * TreeView上に表示する行名。
     *
     * 主に Folder 行で使用します。
     *
     * Editor 行の場合は、実際の表示内容を [EditorGraphicFactory] が生成するため、
     * 空文字や補助的な名前でも問題ありません。
     */
    val label: String

    /**
     * この行がどの編集対象・編集範囲に属しているかを表すコンテキスト。
     *
     * 例:
     *
     * - 全体設定
     * - Lore の特定行
     * - Lore の特定セクション
     * - Stats の特定キー
     *
     * など。
     *
     * 実際の中身は各エディタ側の専用クラスで表現します。
     */
    val context: EditorTreeContext

    /**
     * この行が「フォルダ行」なのか「編集UI行」なのかを表す種別。
     *
     * TreeCell側はこの値を見て、
     *
     * - [Kind.Folder] なら `text` で表示
     * - [Kind.Editor] なら [EditorGraphicFactory] に `Node` 生成を任せる
     *
     * という分岐を行います。
     */
    val kind: Kind

    /**
     * TreeView上の行の大分類。
     */
    enum class Kind {

        /**
         * 折りたたみ可能な分類・グループ行。
         *
         * 例:
         *
         * - Lore
         * - 1行目
         * - Stats
         * - Display
         */
        Folder,

        /**
         * 実際の編集UIを表示する行。
         *
         * 例:
         *
         * - TextField
         * - ComboBox
         * - Button
         * - ToggleSwitch
         * - 独自のHBox/VBox
         */
        Editor
    }
}

/**
 * エディタ用ツリーの行が持つ「編集コンテキスト」の共通インターフェース。
 *
 * コンテキストは、その行がどのデータ範囲に紐づいているかを表すために使います。
 *
 * 例えばItemエディタなら、
 *
 * ```kotlin
 * sealed class ItemTreeContext : EditorTreeContext {
 *     data object Global : ItemTreeContext()
 *     data class LoreLine(val lineIndex: Int) : ItemTreeContext()
 *     data class LoreSection(
 *         val lineIndex: Int,
 *         val sectionIndex: Int
 *     ) : ItemTreeContext()
 * }
 * ```
 *
 * のように定義できます。
 *
 * 共通側では中身を解釈せず、型として保持するだけです。
 * 実際の判定や処理は各エディタ側で行います。
 */
interface EditorTreeContext

/**
 * TreeViewの構造を構築・再構築するための共通インターフェース。
 *
 * 各エディタは、このインターフェースを実装して、
 * 自分専用のデータ構造を TreeItem の階層へ変換します。
 *
 * 例:
 *
 * - ItemBaseData → TreeItem<ItemTreeRow>
 * - SkillData → TreeItem<SkillTreeRow>
 * - QuestData → TreeItem<QuestTreeRow>
 *
 * TreeViewの見た目やセル処理は共通化しつつ、
 * ツリー構造の作り方だけをエディタごとに差し替えるためのインターフェースです。
 *
 * @param R TreeViewに表示する行型。各エディタ専用の [EditorTreeRow] 実装を指定します。
 */
interface EditorTreeBuilder<R : EditorTreeRow> {

    /**
     * ツリーのルート配下を再構築します。
     *
     * 通常は以下のような処理を行います。
     *
     * - `rootItem.children.clear()`
     * - 現在の編集データを読み取る
     * - 必要な `TreeItem<R>` を作る
     * - `rootItem.children` に追加する
     *
     * このメソッドは、データの追加・削除・並び替え後に、
     * TreeViewの表示を最新状態へ戻すためにも使います。
     *
     * @param rootItem TreeViewのルートTreeItem。
     */
    fun rebuildRoot(rootItem: TreeItem<R>)
}

/**
 * [EditorTreeRow] からJavaFXの編集UI [Node] を生成するための共通インターフェース。
 *
 * [EditorTreeCell] は行の種別が [EditorTreeRow.Kind.Editor] の場合、
 * 具体的なUI生成をこのFactoryに委譲します。
 *
 * これにより、TreeCell自体は共通化したまま、
 *
 * - Item用のTextField
 * - Skill用のComboBox
 * - Quest用のButton
 *
 * などをエディタごとに自由に作れます。
 *
 * @param R TreeViewに表示する行型。
 */
interface EditorGraphicFactory<R : EditorTreeRow> {

    /**
     * 指定された行に対応する編集UIを生成します。
     *
     * 戻り値には、`TextField`、`ComboBox`、`Button`、`HBox`、`VBox` など、
     * 任意のJavaFX [Node] を返せます。
     *
     * 注意点:
     *
     * - このメソッドはTreeCellの更新時に呼ばれるため、必要以上に重い処理は避けてください。
     * - TextFieldなどにListenerを付ける場合、セル再利用による重複登録に注意してください。
     * - 実際のデータ更新処理は、各エディタ側のFactory内で行います。
     *
     * @param row UIを生成する対象行。
     * @return TreeCellのgraphicに設定するJavaFX Node。
     */
    fun createGraphic(row: R): Node
}

/**
 * TreeView内でドラッグ＆ドロップ可能かどうかを判定するための共通インターフェース。
 *
 * 共通のTreeCellは、ドラッグ中のTreeItemとドロップ先TreeItemをこのValidatorへ渡し、
 * ドロップを許可するかどうかを判定します。
 *
 * 具体的なルールはエディタごとに異なるため、共通側では持ちません。
 *
 * 例:
 *
 * - Lore行同士だけ移動可能
 * - 同じ親を持つSection同士だけ移動可能
 * - Folderにはドロップ不可
 * - Root直下では移動不可
 *
 * など。
 *
 * @param R TreeViewに表示する行型。
 */
interface TreeDragValidator<R : EditorTreeRow> {

    /**
     * 指定されたsourceをtargetへドロップしてよいかを判定します。
     *
     * @param source ドラッグ元のTreeItem。ドラッグ元が不明な場合はnull。
     * @param target ドロップ先のTreeItem。ドロップ先が不明な場合はnull。
     * @return ドロップを許可する場合はtrue、禁止する場合はfalse。
     */
    fun canDrop(
        source: TreeItem<R>?,
        target: TreeItem<R>?
    ): Boolean
}

/**
 * TreeView内でドラッグ＆ドロップが成立したとき、
 * 実際のデータ移動を行うための共通インターフェース。
 *
 * [TreeDragValidator] は「移動してよいか」を判定するだけですが、
 * この [TreeMoveHandler] は「実際にデータを並び替える」責務を持ちます。
 *
 * 例えばItemエディタなら、
 *
 * - Lore行の順番を入れ替える
 * - Lore内のSectionの順番を入れ替える
 * - ItemBaseData側のリストを更新する
 *
 * といった処理を行います。
 *
 * TreeItemの見た目だけを移動しても保存データには反映されないため、
 * 必ず編集対象データ側も更新する必要があります。
 *
 * @param R TreeViewに表示する行型。
 */
interface TreeMoveHandler<R : EditorTreeRow> {

    /**
     * sourceをtargetの位置へ移動します。
     *
     * このメソッドでは、TreeItemの入れ替えそのものよりも、
     * 編集対象データの並び替えを行うことを想定しています。
     *
     * UIの再構築は、呼び出し元のTreeCellやEditorLogic側で行う設計にすると安全です。
     *
     * @param source 移動元のTreeItem。
     * @param target 移動先のTreeItem。
     * @return 移動に成功した場合はtrue、失敗または未対応の場合はfalse。
     */
    fun move(
        source: TreeItem<R>,
        target: TreeItem<R>
    ): Boolean
}

/**
 * Folder行に独自のGraphicを表示したい場合に使うFactory。
 *
 * nullを返した場合は、通常通り row.label が text として表示されます。
 */
fun interface EditorFolderGraphicFactory<R : EditorTreeRow> {
    fun createFolderGraphic(row: R): Node?
}

fun interface EditorContextMenuFactory<R : EditorTreeRow> {
    fun createContextMenu(row: R): ContextMenu?
}